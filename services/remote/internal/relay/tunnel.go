package relay

import (
    "context"
    "errors"
    "io"
    "net"
    "net/http"
    "net/http/httputil"
    "net/url"
    "sync"
    "sync/atomic"
    "time"

    "github.com/coder/websocket"
)

const tunnelAuthority = "dsh-api:80"
var ErrTunnelAdmission = errors.New("relay: tunnel admission refused")

type TunnelLease struct {
    ID string
    DeviceID string
    UserID string
    RuntimeID string
    GrantVersion int64
    ExpiresAt time.Time
}

type tunnelSecretKey struct{}
type tunnelPool struct {
    lease TunnelLease
    transport *http.Transport
    conns map[*connectConn]struct{}
    expiry *time.Timer
}

// TunnelRuntime carries opaque HTTP bytes. It never inspects a dsh payload.
type TunnelRuntime struct {
    ctx context.Context
    cancel context.CancelFunc
    socket *websocket.Conn
    wire net.Conn
    h2 *http.Transport
    handed atomic.Bool
    mu sync.Mutex
    pools map[string]*tunnelPool
    active int
    closed bool
}

func NewTunnelRuntime(parent context.Context, socket *websocket.Conn) *TunnelRuntime {
    ctx, cancel := context.WithCancel(parent)
    wire := websocket.NetConn(ctx, socket, websocket.MessageBinary)
    // NetConn disables the websocket read limit; apply our message budget afterwards.
    socket.SetReadLimit(1 << 20)
    runtime := &TunnelRuntime{ctx:ctx, cancel:cancel, socket:socket, wire:wire, pools:make(map[string]*tunnelPool)}
    protocols := new(http.Protocols)
    protocols.SetUnencryptedHTTP2(true)
    runtime.h2 = &http.Transport{
        Protocols:protocols, MaxConnsPerHost:1, MaxResponseHeaderBytes:32 << 10,
        DialContext:func(ctx context.Context, network, address string)(net.Conn,error) {
            if ctx.Err()!=nil || runtime.ctx.Err()!=nil || !runtime.handed.CompareAndSwap(false,true) { return nil,ErrTunnelAdmission }
            return &messageBoundConn{Conn:wire},nil
        },
    }
    return runtime
}

type messageBoundConn struct { net.Conn }
func (conn *messageBoundConn) Write(data []byte)(int,error) {
    total:=0
    for len(data)>0 {
        size:=len(data); if size>64<<10 { size=64<<10 }
        count,err:=conn.Conn.Write(data[:size]); total+=count; data=data[count:]
        if err!=nil { return total,err }; if count==0 { return total,io.ErrShortWrite }
    }
    return total,nil
}

func (runtime *TunnelRuntime) Proxy(response http.ResponseWriter, request *http.Request, lease TunnelLease, secret string) {
    if lease.ID=="" || secret=="" || time.Now().After(lease.ExpiresAt) { http.Error(response,"lease refused",http.StatusUnauthorized); return }
    runtime.mu.Lock()
    if runtime.closed { runtime.mu.Unlock(); http.Error(response,"runtime offline",http.StatusServiceUnavailable); return }
    pool:=runtime.pools[lease.ID]
    if pool!=nil && (pool.lease.GrantVersion!=lease.GrantVersion || pool.lease.DeviceID!=lease.DeviceID || pool.lease.RuntimeID!=lease.RuntimeID) {
        runtime.mu.Unlock(); http.Error(response,"lease changed",http.StatusUnauthorized); return
    }
    if pool==nil {
        pool=&tunnelPool{lease:lease,conns:make(map[*connectConn]struct{})}
        protocols:=new(http.Protocols); protocols.SetHTTP1(true)
        pool.transport=&http.Transport{Protocols:protocols,MaxConnsPerHost:8,MaxIdleConnsPerHost:8,MaxResponseHeaderBytes:32<<10,ResponseHeaderTimeout:0,
            DialContext:func(ctx context.Context, network,address string)(net.Conn,error){
                token,_:=ctx.Value(tunnelSecretKey{}).(string)
                return runtime.connect(ctx,lease.ID,token)
            },
        }
        pool.expiry=time.AfterFunc(time.Until(lease.ExpiresAt),func(){runtime.RevokeLease(lease.ID)})
        runtime.pools[lease.ID]=pool
    }
    transport:=pool.transport
    runtime.mu.Unlock()
    proxy:=httputil.ReverseProxy{Transport:transport,FlushInterval:-1,
        Rewrite:func(proxy *httputil.ProxyRequest){
            proxy.Out.URL.Scheme="http"; proxy.Out.URL.Host=tunnelAuthority
            for _,name:=range []string{"Authorization","DPoP","Proxy-Authorization","Cookie","X-TokenWorks-Runtime","X-TokenWorks-Lease","X-TokenWorks-Lease-Id","Forwarded","X-Forwarded-For","X-Forwarded-Host","X-Forwarded-Proto"} { proxy.Out.Header.Del(name) }
        },
        ErrorHandler:func(w http.ResponseWriter,r *http.Request,err error){ println("tunnel proxy error:",err.Error()); http.Error(w,"native transport unavailable; write outcome may be unknown",http.StatusBadGateway) },
    }
    proxy.ServeHTTP(response,request.WithContext(context.WithValue(request.Context(),tunnelSecretKey{},secret)))
}

func (runtime *TunnelRuntime) connect(dial context.Context, leaseID, secret string)(net.Conn,error) {
    if secret=="" { println("tunnel connect: missing secret"); return nil,ErrTunnelAdmission }
    runtime.mu.Lock()
    pool:=runtime.pools[leaseID]
    if runtime.closed || pool==nil || runtime.active>=128 || len(pool.conns)>=8 || time.Now().After(pool.lease.ExpiresAt) {
        println("tunnel connect: refused closed=",runtime.closed,"poolnil=",pool==nil,"active=",runtime.active,"conns=",len(pool.conns))
        runtime.mu.Unlock(); return nil,ErrTunnelAdmission }
    runtime.active++
    // Reserve before the CONNECT response so concurrent dials cannot exceed the lease budget.
    placeholder:=&connectConn{}
    pool.conns[placeholder]=struct{}{}
    runtime.mu.Unlock()
    var admitted bool
    defer func(){if !admitted {runtime.release(leaseID,placeholder)}}()
    lifetime,cancel:=context.WithCancel(runtime.ctx)
    timer:=time.AfterFunc(15*time.Second,cancel)
    stopped:=make(chan struct{})
    go func(){select{case <-dial.Done():cancel();case <-stopped:case <-lifetime.Done():}}()
    reader,writer:=io.Pipe()
    request:=&http.Request{Method:http.MethodConnect,URL:&url.URL{Scheme:"http",Host:tunnelAuthority},Host:tunnelAuthority,Header:make(http.Header),Body:reader}
    request.Header.Set("Proxy-Authorization","Bearer "+secret)
    request.Header.Set("X-TokenWorks-Lease-Id",leaseID)
    response,err:=runtime.h2.RoundTrip(request.WithContext(lifetime))
    close(stopped); timer.Stop()
    if err!=nil {println("tunnel connect: roundtrip error:",err.Error());cancel();reader.Close();writer.Close();return nil,err}
    if response.StatusCode!=http.StatusOK {println("tunnel connect: status",response.StatusCode);cancel();response.Body.Close();reader.Close();writer.Close();return nil,ErrTunnelAdmission}
    conn:=&connectConn{reader:response.Body,writer:writer,cancel:cancel}
    conn.onClose=func(){runtime.release(leaseID,conn)}
    runtime.mu.Lock()
    pool=runtime.pools[leaseID]
    if runtime.closed || pool==nil {runtime.mu.Unlock();cancel();response.Body.Close();writer.Close();return nil,ErrTunnelAdmission}
    delete(pool.conns,placeholder);pool.conns[conn]=struct{}{}
    admitted=true
    runtime.mu.Unlock()
    return conn,nil
}

func(runtime *TunnelRuntime) release(id string,conn *connectConn) {
    runtime.mu.Lock();defer runtime.mu.Unlock()
    if pool:=runtime.pools[id];pool!=nil {if _,ok:=pool.conns[conn];ok {delete(pool.conns,conn);runtime.active--}}
}

func(runtime *TunnelRuntime) RevokeLease(id string) {
    runtime.mu.Lock()
    pool:=runtime.pools[id]
    if pool==nil {runtime.mu.Unlock();return}
    delete(runtime.pools,id);runtime.active-=len(pool.conns)
    conns:=make([]*connectConn,0,len(pool.conns));for conn:=range pool.conns {conns=append(conns,conn)}
    runtime.mu.Unlock()
    pool.expiry.Stop();pool.transport.CloseIdleConnections()
    for _,conn:=range conns {conn.Close()}
}

func(runtime *TunnelRuntime) RevokeDevice(deviceID string) {
    runtime.mu.Lock();ids:=[]string{}
    for id,pool:=range runtime.pools {if pool.lease.DeviceID==deviceID {ids=append(ids,id)}}
    runtime.mu.Unlock();for _,id:=range ids {runtime.RevokeLease(id)}
}

func(runtime *TunnelRuntime) Close() {
    runtime.mu.Lock();if runtime.closed {runtime.mu.Unlock();return};runtime.closed=true
    ids:=make([]string,0,len(runtime.pools));for id:=range runtime.pools {ids=append(ids,id)};runtime.mu.Unlock()
    for _,id:=range ids {runtime.RevokeLease(id)}
    runtime.cancel();runtime.h2.CloseIdleConnections();runtime.wire.Close();runtime.socket.CloseNow()
}

type connectAddr struct{}
func(connectAddr) Network()string{return "h2-connect"}
func(connectAddr) String()string{return tunnelAuthority}

type connectConn struct {
    reader io.ReadCloser
    writer *io.PipeWriter
    cancel context.CancelFunc
    once sync.Once
    onClose func()
    deadlineMu sync.Mutex
    readTimer *time.Timer
    writeTimer *time.Timer
}
func(conn *connectConn) Read(data []byte)(int,error){return conn.reader.Read(data)}
func(conn *connectConn) Write(data []byte)(int,error){return conn.writer.Write(data)}
func(conn *connectConn) Close()error{
    conn.once.Do(func(){
        conn.deadlineMu.Lock();if conn.readTimer!=nil {conn.readTimer.Stop()};if conn.writeTimer!=nil {conn.writeTimer.Stop()};conn.deadlineMu.Unlock()
        if conn.cancel!=nil {conn.cancel()};if conn.reader!=nil {conn.reader.Close()};if conn.writer!=nil {conn.writer.Close()};if conn.onClose!=nil {conn.onClose()}
    });return nil
}
func(conn *connectConn) LocalAddr()net.Addr{return connectAddr{}}
func(conn *connectConn) RemoteAddr()net.Addr{return connectAddr{}}
func(conn *connectConn) SetDeadline(deadline time.Time)error{conn.SetReadDeadline(deadline);return conn.SetWriteDeadline(deadline)}
func(conn *connectConn) SetReadDeadline(deadline time.Time)error{
    conn.deadlineMu.Lock();defer conn.deadlineMu.Unlock();if conn.readTimer!=nil {conn.readTimer.Stop()};if !deadline.IsZero(){conn.readTimer=time.AfterFunc(time.Until(deadline),func(){conn.Close()})};return nil
}
func(conn *connectConn) SetWriteDeadline(deadline time.Time)error{
    conn.deadlineMu.Lock();defer conn.deadlineMu.Unlock();if conn.writeTimer!=nil {conn.writeTimer.Stop()};if !deadline.IsZero(){conn.writeTimer=time.AfterFunc(time.Until(deadline),func(){conn.Close()})};return nil
}
