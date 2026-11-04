'use strict';
const $ = id => document.getElementById(id);
let mode = 'login', csrf = '', generation = 0, busy = false, expiryTimer;
const errors = {
 'account.credentials_invalid':'用户名或密码错误，或登录已失效。',
 'account.username_unavailable':'此用户名不可用，请换一个用户名。',
 'account.input_invalid':'请检查输入。用户名为 3–32 位字母、数字或 _ . -，新密码至少 12 个字符。',
 'account.rate_limited':'尝试次数过多，请在十分钟后重试。',
 'account.csrf_invalid':'页面登录状态已变化，请刷新后再试。',
 'account.registration_disabled':'当前服务未开放注册。',
 'account.unavailable':'服务暂时不可用，请稍后重试。',
};
async function api(path, body) {
 const response = await fetch('/account/api/' + path, { method:body === undefined?'GET':'POST',
  credentials:'same-origin', cache:'no-store', redirect:'error', signal:AbortSignal.timeout(15000),
  headers:body===undefined?{}:{'Content-Type':'application/json','X-CSRF-Token':csrf},
  ...(body===undefined?{}:{body:JSON.stringify(body)}) });
 const value = await response.json();
 if (!response.ok || !value.ok) {const error=new Error(errors[value.error?.code]||'请求失败，请刷新页面后重试。'); error.status=response.status;throw error;}
 return value.data;
}
function message(error){return error.name==='TimeoutError'?'请求超时，操作结果尚未确认，请先刷新状态。':error.message||'无法连接服务，请稍后重试。';}
function setBusy(value){busy=value;document.querySelectorAll('form button, .actions button, .tab').forEach(button=>button.disabled=value);}
function switchMode(value){if(busy)return;mode=value;const register=mode==='register';
 $('login-tab').classList.toggle('selected',!register);$('register-tab').classList.toggle('selected',register);
 $('login-tab').setAttribute('aria-pressed',String(!register));$('register-tab').setAttribute('aria-pressed',String(register));
 $('form-title').textContent=register?'创建中转账号':'欢迎回来';$('form-description').textContent=register?'先创建身份，再由组织与主机授予访问权限。':'使用你的中转服务账号登录。';
 $('submit').textContent=register?'创建账号':'登录';$('password').autocomplete=register?'new-password':'current-password';$('password').minLength=register?12:1;
 $('password-help').hidden=!register;$('confirm-label').hidden=!register;$('confirm').hidden=!register;$('confirm').required=register;
 $('auth-error').textContent='';$('password').value='';$('confirm').value='';}
function signedOut(notice=''){generation++;clearTimeout(expiryTimer);csrf='';$('dashboard').hidden=true;$('auth').hidden=false;$('loading').hidden=true;$('password-form').reset();for(const id of ['greeting','account-name','account-id','expires','host-count','memberships','hosts'])$(id).replaceChildren();$('notice').textContent=notice;}
function list(id,items,empty,render){const node=$(id);node.replaceChildren();if(!items.length){const li=document.createElement('li');li.className='empty';li.textContent=empty;node.append(li);return;}for(const item of items){const li=document.createElement('li');render(li,item);node.append(li);}}
async function refresh(){const version=++generation;
 try{const data=await api('session');if(version!==generation)return;csrf=data.csrf;
  clearTimeout(expiryTimer);expiryTimer=setTimeout(()=>signedOut('登录已到期，请重新登录。'),Math.max(0,Date.parse(data.session.expires_at)-Date.now()));
  $('auth').hidden=true;$('dashboard').hidden=false;$('loading').hidden=true;$('dashboard-error').textContent='';
  $('greeting').textContent='你好，'+data.session.username;$('account-name').textContent=data.session.username;$('account-id').textContent=data.session.user_id;$('expires').textContent=new Date(data.session.expires_at).toLocaleString();
  list('memberships',data.identity.memberships,'尚未加入组织，请联系服务管理员分配组织关系。',(li,item)=>{li.textContent=item.organization_id+' · '+(item.role==='admin'?'管理员':'成员');});
  list('hosts',data.hosts,'暂无已授权主机。组织关系本身不会授予远控权限。',(li,item)=>{const name=document.createElement('strong');name.textContent=item.alias;const detail=document.createElement('div');detail.className='mono muted';detail.textContent=item.id;li.append(name,detail);});
  $('host-count').textContent=String(data.hosts.length);
 }catch(error){if(version!==generation)return;signedOut();if(error.status!==401&&error.status!==403)$('auth-error').textContent=message(error);}
}
$('login-tab').addEventListener('click',()=>switchMode('login'));$('register-tab').addEventListener('click',()=>switchMode('register'));
$('auth-form').addEventListener('submit',async event=>{event.preventDefault();if(busy)return;$('auth-error').textContent='';
 if(mode==='register'&&$('password').value!==$('confirm').value){$('auth-error').textContent='两次输入的密码不一致。';$('confirm').focus();return;}
 const payload={username:$('username').value,password:$('password').value};setBusy(true);
 try{if(mode==='register'){await api('register',payload);$('password').value='';$('confirm').value='';setBusy(false);switchMode('login');$('notice').textContent='账号已创建，请登录。';$('password').focus();}
 else {const data=await api('login',payload);csrf=data.csrf;$('password').value='';$('notice').textContent='';await refresh();}}
 catch(error){$('auth-error').textContent=message(error);}finally{payload.password='';setBusy(false);}
});
$('refresh').addEventListener('click',async()=>{if(busy)return;setBusy(true);try{await refresh();}finally{setBusy(false);}});
$('logout').addEventListener('click',async()=>{if(busy)return;setBusy(true);try{await api('logout',{});signedOut('你已退出登录。');}catch(error){$('dashboard-error').textContent=message(error);}finally{setBusy(false);}});
$('password-form').addEventListener('submit',async event=>{event.preventDefault();if(busy)return;$('password-error').textContent='';
 if($('new-password').value!==$('new-confirm').value){$('password-error').textContent='两次输入的新密码不一致。';$('new-confirm').focus();return;}
 const payload={current_password:$('current-password').value,password:$('new-password').value};setBusy(true);
 try{await api('password',payload);signedOut('密码已更新，所有浏览器会话已退出，请重新登录。');$('password').focus();}
 catch(error){$('password-error').textContent=message(error);}finally{payload.current_password='';payload.password='';setBusy(false);}
});
window.addEventListener('pageshow',event=>{if(event.persisted)void refresh();});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&!busy)void refresh();});
(async()=>{try{const data=await api('providers');$('register-tab').hidden=!data.registration;await refresh();}catch(error){$('loading').hidden=true;$('auth').hidden=false;$('auth-error').textContent=message(error);}})();
