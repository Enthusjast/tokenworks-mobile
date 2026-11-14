import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Application-maintained MIT dependency patch. Both runtime entry points are
// patched: Node ESM uses the bundled index.mjs, not the CJS source tree.
// Exact original AND patched digests are recorded in the adjacent manifest.
export const upstream = {
  'lib/index.mjs': 'ac6fba0934023c645c63ea6c67a8bcf322f37cac18fbd50a91bcb8aece4cb164',
  'lib/sctp/src/sctp.js': 'cfc6149930815571bc1cf294ae65cf49806b0599bec42f7439d673068b719a4b',
  'lib/dtls/src/socket.js': '22588d39e6d208a05e02a2ef8a2a070e946213c2d51720d3390eba4fc87f60cd',
  'lib/webrtc/src/transport/sctp.js': 'ab710a82a342c656b91220de19f29f98123f0f38a8f6602a93d36649fe9c7038',
  'lib/webrtc/src/transport/dtls.js': '3b51c2b966926bd9103b67e39fdc68ae530d3ac15bf0cd3e90b9caa5de85f02e',
  'lib/webrtc/src/peerConnection.js': 'b52b96cdc1ad92bea12a716326708017a4e78a03f4e08645aa639dbab066ba56',
  'lib/ice/src/ice.js': 'd4ca708aeef410580a1ac09d3a2881060dcee0b455e1bb45cb33f2b536f4235c',
  'lib/common/src/transport.js': 'df45bf5f2f732a8ea3d3b5ae99977c372e6792c6e6aaa78db383ab2655c0bc2c',
  'lib/webrtc/src/transport/sctp.d.ts': '27bc7fc755738bb58c37dbf5e8d42edbe29e8926681025b5f1e60fb603aa9cba',
}
export function transform(path, input) {
  let source = input
  const replace = (old, next) => {
    if (source.split(old).length !== 2) throw new Error(`werift patch anchor mismatch: ${path}`)
    source = source.replace(old, next)
  }
  const esm = path === 'lib/index.mjs', indent = esm ? '  ' : '    '
  if (esm || path === 'lib/webrtc/src/peerConnection.js') {
    replace(`${indent}async close() {\n${indent}${indent}if (this.isClosed)`, `${indent}close() {
      if (this.carrierClosePromise) return this.carrierClosePromise;
      const pending = Promise.withResolvers(); this.carrierClosePromise = pending.promise;
      this.closeOnce().then(pending.resolve, pending.reject);
      return pending.promise;
    }
${indent}async closeOnce() {
${indent}${indent}if (this.isClosed)`)
  }
  if (esm || path === 'lib/sctp/src/sctp.js') {
    replace(`${indent}get maxChannels() {`, `${indent}getCarrierQueueBytes() {
      let inbound = (this.carrierTasks?.inbound ?? 0) + this.sackDuplicates.length * 8 + this.sackMisOrdered.size * 16, outbound = this.carrierTasks?.outbound ?? 0;
      for (const stream of Object.values(this.inboundStreams)) for (const chunk of stream.reassembly) inbound += chunk.userData.length + 64;
      for (const chunk of this.outboundQueue) outbound += chunk.userData.length + 64;
      for (const chunk of this.sentQueue) outbound += chunk.userData.length + 64;
      return { inbound, outbound };
    }
${indent}get maxChannels() {`)
    replace(`${indent}async handleData(data) {`, `${indent}async handleData(data) {
      const guard = this.carrierGuard, tasks = this.carrierTasks;
      if (guard) {
        try {
          if (tasks.receive >= 32 || data.length > 18432) throw new Error("rtc/sctp-task-budget");
          guard.reserve(data.length + 128, 0);
        } catch (error) { guard.fail(error); return; }
        tasks.receive++; tasks.inbound += data.length + 128; tasks.maxReceive = Math.max(tasks.maxReceive, tasks.receive);
      }
      try {`)
    replace(`${indent}}\n${indent}async scheduleSack() {`, `      } finally { if (guard) { tasks.receive--; tasks.inbound -= data.length + 128; this.carrierDrained?.(); } }
${indent}}
${indent}async scheduleSack() {`)
    replace(`${indent}receiveDataChunk(chunk) {`, `${indent}receiveDataChunk(chunk) {
      if (this.carrierGuard) {
        let bytes = 0, count = 0;
        for (const stream of Object.values(this.inboundStreams)) for (const item of stream.reassembly) { bytes += item.userData.length; count++; }
        try {
          if ((this.carrierChannel?.id !== undefined && this.carrierChannel.id !== chunk.streamId) || Object.keys(this.inboundStreams).length > 1 || count >= 128 || this.sackDuplicates.length >= 128 || this.sackMisOrdered.size >= 128 || bytes + chunk.userData.length > 16384) throw new Error("rtc/sctp-reassembly-budget");
          this.carrierGuard.reserve(chunk.userData.length + 64, 0);
        } catch (error) { this.carrierGuard.fail(error); return; }
      }`)
    const sendAnchor = esm ? '    const streamSeqNum = ordered ? this.outboundStreamSeq[streamId] || 0 : 0;' : '                const streamSeqNum = ordered ? this.outboundStreamSeq[streamId] || 0 : 0;'
    replace(sendAnchor, `      if (this.carrierGuard) {
        const queue = this.getCarrierQueueBytes();
        try {
          if (userData.length > 16384 || queue.outbound + userData.length + 128 * (Math.ceil(userData.length / 1000) + 1) > 262144) throw new Error("rtc/sctp-send-budget");
          this.carrierGuard.reserve(0, userData.length + 128 * (Math.ceil(userData.length / 1000) + 1));
        } catch (error) { this.carrierGuard.fail(error); throw error; }
      }
${sendAnchor}`)
    replace(`${indent}async sendChunk(chunk) {`, `${indent}async sendChunk(chunk) {
      const guard = this.carrierGuard, tasks = this.carrierTasks;
      const bytes = (chunk.userData?.length ?? 18432) + 128;
      // The empty library ABORT uses the already-charged DTLS last-flight
      // reservation; a fenced application must not veto its own teardown.
      const shutdown = this.isStopping && chunk.type === ${esm ? 'AbortChunk' : 'chunk_1.AbortChunk'}.type;
      if (guard) {
        try { if (tasks.send >= 32) throw new Error("rtc/sctp-send-task-budget"); if (!shutdown) guard.reserve(0, bytes); }
        catch (error) { guard.fail(error); throw error; }
        tasks.send++; tasks.outbound += bytes; tasks.maxSend = Math.max(tasks.maxSend, tasks.send);
      }
      try {`)
    replace(`await this.transport.send(packet);\n${indent}}\n${indent}setState(${esm ? 'state2' : 'state'}) {`, `await this.transport.send(packet);
      } finally { if (guard) { tasks.send--; tasks.outbound -= bytes; this.carrierDrained?.(); } }
${indent}}
${indent}setState(${esm ? 'state2' : 'state'}) {`)
    replace('this.releaseSendWaiters();', `this.releaseSendWaiters();
      if (this.carrierGuard) {
        this.outboundQueue.clear(); this.sentQueue.clear(); this.inboundStreams = {};
        this.sackDuplicates = []; this.sackMisOrdered.clear(); this.reconfigQueue = []; this.deferredReset = undefined;
      }`)
    replace(`${indent}async receiveSackChunk(chunk) {`, `${indent}async receiveSackChunk(chunk) {
      if (this.carrierGuard && chunk.gaps.length) this.carrierTasks.gapAcks++;`)
    replace('dataChunk.sentCount++;', 'dataChunk.sentCount++; if (this.carrierGuard) this.carrierTasks.retransmits++;')
  }
  if (esm || path === 'lib/dtls/src/socket.js') {
    replace('this.onHandleHandshakes(assembled).catch((error) => {', 'this.handleCarrierHandshakes(assembled).catch((error) => {')
    replace(`${indent}handleFragmentHandshake(messages) {`, `${indent}async handleCarrierHandshakes(messages) {
      if (messages.length === 0) return;
      const guard = this.carrierGuard, tasks = this.carrierTasks;
      let bytes = 0;
      for (const message of messages) bytes += message.fragment.length + 128;
      if (guard) {
        try { if (tasks.handshakes >= 8) throw new Error("rtc/dtls-task-budget"); guard.reserve(bytes, 0); }
        catch (error) { guard.fail(error); return; }
        tasks.handshakes++; tasks.inbound += bytes; tasks.maxHandshakes = Math.max(tasks.maxHandshakes, tasks.handshakes);
      }
      try { await this.onHandleHandshakes(messages); }
      finally { if (guard) { tasks.handshakes--; tasks.inbound -= bytes; this.carrierDrained?.(); } }
    }
${indent}handleFragmentHandshake(messages) {
      if (this.carrierGuard) {
        let retained = 0, incoming = 0;
        for (const fragment of this.bufferFragmentedHandshakes) retained += fragment.fragment.length + 128;
        try {
          for (const message of messages) { if (message.length > 65536) throw new Error("rtc/dtls-message-budget"); incoming += message.fragment.length + 128; }
          if (this.bufferFragmentedHandshakes.length + messages.length > 64 || retained + incoming > 65536 || this.carrierTasks.total + incoming > 65536) throw new Error("rtc/dtls-fragment-budget");
          this.carrierGuard.reserve(incoming, 0); this.carrierTasks.total += incoming;
        } catch (error) { this.carrierGuard.fail(error); return []; }
      }`)
    replace(`${indent}close() {\n${indent}${indent}this.transport.close();\n${indent}}`, `${indent}close() {
      this.transport.close();
      if (this.carrierGuard) {
        this.carrierClosed = true; this.bufferFragmentedHandshakes = [];
        this.dtls.handshakeCache = {}; this.dtls.lastFlight = []; this.dtls.lastMessage = [];
      }
    }`)
  }
  if (esm || path === 'lib/webrtc/src/transport/sctp.js') {
    const marker = `${indent}channelByLabel(label) {`
    replace(marker, `${indent}configureCarrierResources(guard) {
      if (this.carrierGuard || !guard || typeof guard.reserve !== "function" || typeof guard.fail !== "function") throw new Error("rtc/library-resource-contract");
      guard.reserve(65536, 65536);
      this.carrierGuard = guard;
      this.sctp.carrierGuard = guard;
      this.sctp.carrierTasks = { inbound: 0, outbound: 0, receive: 0, send: 0, maxReceive: 0, maxSend: 0, gapAcks: 0, retransmits: 0 };
      this.dtlsTransport.carrierGuard = guard;
    }
${indent}admitCarrierChannel(channel) {
      if (!this.carrierGuard || (this.sctp.carrierChannel && this.sctp.carrierChannel !== channel)) throw new Error("rtc/extra-channel");
      this.sctp.carrierChannel = channel;
    }
${indent}drainCarrierResources() {
      if (this.dtlsTransport.state !== "closed") throw new Error("rtc/drain-before-close");
      if (this.carrierDrain) return this.carrierDrain;
      const pending = Promise.withResolvers(); this.carrierDrain = pending.promise;
      const socket = this.dtlsTransport.dtls;
      const finish = () => {
        if ((this.sctp.carrierTasks?.receive ?? 0) || (this.sctp.carrierTasks?.send ?? 0) || (socket?.carrierTasks?.handshakes ?? 0)) return;
        if (socket?.carrierGuard) { socket.bufferFragmentedHandshakes = []; socket.dtls.handshakeCache = {}; socket.dtls.lastFlight = []; socket.dtls.lastMessage = []; }
        this.dataChannelQueue.clear(); pending.resolve();
      };
      this.sctp.carrierDrained = finish;
      if (socket) socket.carrierDrained = finish;
      finish(); return pending.promise;
    }
${indent}getCarrierResourceStats() {
      const tasks = this.sctp.carrierTasks, dtls = this.dtlsTransport.dtls?.carrierTasks;
      return { maxReceiveTasks: tasks?.maxReceive ?? 0, maxSendTasks: tasks?.maxSend ?? 0,
        maxHandshakeTasks: dtls?.maxHandshakes ?? 0, gapAcks: tasks?.gapAcks ?? 0, retransmits: tasks?.retransmits ?? 0 };
    }
${indent}getCarrierQueueBytes() {
      const queue = this.sctp.getCarrierQueueBytes();
      let pendingWriteBytes = queue.outbound;
      for (const entry of this.dataChannelQueue) pendingWriteBytes += entry[2].length + 64;
      if (!this.carrierGuard) return { ...queue, pendingWriteBytes };
      // Fixed charges bound retained transcript and last-flight buffers. Incoming
      // handshake input has a matching 64KiB lifetime cap at the original parser.
      const socket = this.dtlsTransport.dtls;
      if (this.dtlsTransport.state !== "closed" || (socket && !socket.carrierClosed)) { queue.inbound += 65536; queue.outbound += 65536; }
      if (socket?.carrierTasks) {
        queue.inbound += socket.carrierTasks.inbound;
        for (const item of socket.bufferFragmentedHandshakes) queue.inbound += item.fragment.length + 128;
      }
      for (const entry of this.dataChannelQueue) queue.outbound += entry[2].length + 64;
      queue.inbound += Math.min(this.dtlsTransport.iceTransport.connection.earlyChecks.length, 3) * 65536;
      return { ...queue, pendingWriteBytes };
    }
${marker}`)
  }
  if (esm || path === 'lib/webrtc/src/transport/dtls.js') {
    const marker = `${esm ? '      ' : '            '}this.dtls.onData.subscribe((buf) => {`
    replace(marker, `${esm ? '      ' : '            '}if (this.carrierGuard) {
        this.dtls.carrierGuard = this.carrierGuard;
        this.dtls.carrierTasks = { inbound: 0, handshakes: 0, total: 0, maxHandshakes: 0 };
      }
${marker}`)
  }
  if (esm || path === 'lib/ice/src/ice.js') {
    replace('this.earlyChecks.push([msg, addr, protocol]);', `if (this.earlyChecks.length >= 2) { this.setState("failed"); return; }
                this.earlyChecks.push([msg, addr, protocol]);`)
    // Candidate SDP is capped by the application; this additionally prevents
    // peer-reflexive requests from growing the check list without a bound.
    const marker = `${indent}checkIncoming(message, addr, protocol) {`
    replace(marker, `${marker}
      if (this.checkList.length >= 128 || this.remoteCandidates.length >= 32) { this.setState("failed"); return; }`)
    replace(`this.lookup = ${esm ? 'void 0' : 'undefined'};\n${indent}${indent}this.remoteResolutions.clear();`, `this.lookup = ${esm ? 'void 0' : 'undefined'};
      this.earlyChecks = [];
${indent}${indent}this.remoteResolutions.clear();`)
    // Explicit UDP means UDP only; upstream otherwise retries TURN over TCP.
    if (esm) replace(`).catch(async (e) => {
      if (transport === "udp" && !this.closing.signal.aborted) {
        return await createStunOverTurnClient(
          { address, username, password },
          {
            portRange: this.options.portRange,
            interfaceAddresses: this.options.interfaceAddresses,
            transport: "tcp",
            connectTimeoutMs: (this.options.turnConnectTimeout ?? 8) * 1e3,
            signal: this.closing.signal
          }
        );
      } else {
        throw e;
      }
    });`, ');')
    else replace(`}).catch(async (e) => {
            // close() cancelled the allocation: no TCP fallback.
            if (transport === "udp" && !this.closing.signal.aborted) {
                return await (0, protocol_2.createStunOverTurnClient)({ address, username, password }, {
                    portRange: this.options.portRange,
                    interfaceAddresses: this.options.interfaceAddresses,
                    transport: "tcp",
                    connectTimeoutMs: (this.options.turnConnectTimeout ?? 8) * 1000,
                    signal: this.closing.signal,
                });
            }
            else {
                throw e;
            }
        });`, '});')
    replace('Promise.allSettled(candidatePromises),', '(this.carrierGathering = Promise.allSettled(candidatePromises)),')
    replace(`this.protocols = [];\n${indent}${indent}this.localCandidates = [];`, `this.protocols = [];
${indent}${indent}this.localCandidates = [];
${indent}${indent}await this.carrierGathering;
${indent}${indent}this.carrierGathering = undefined;`)
  }
  if (esm || path === 'lib/common/src/transport.js') {
    // The upstream IP-literal fast path returned before UDP consumed its buffer.
    // Await the actual callback for literal destinations too. DNS path already
    // uses a callback. Kernel queue size and retained per-socket sends are capped.
    replace('this.socket.send(data, addr[1], addr[0]);', `if ((this.socket.getSendQueueSize?.() ?? 0) + data.length > 262144 || (this.carrierPendingSends ?? 0) >= 32) throw new Error("rtc/udp-send-budget");
      this.carrierPendingSends = (this.carrierPendingSends ?? 0) + 1;
      try {
        await new Promise((resolve, reject) => this.socket.send(data, addr[1], addr[0], error => error ? reject(error) : resolve()));
      } finally { this.carrierPendingSends--; }`)
  }
  if (path === 'lib/webrtc/src/transport/sctp.d.ts') {
    replace('    channelByLabel(label: string): RTCDataChannel | undefined;', `    /** Application-maintained werift 0.27.0 resource patch; not an upstream API. */
    configureCarrierResources(guard: { reserve(inbound: number, outbound: number): void; fail(error: Error): void }): void;
    admitCarrierChannel(channel: RTCDataChannel): void;
    drainCarrierResources(): Promise<void>;
    getCarrierQueueBytes(): { inbound: number; outbound: number; pendingWriteBytes: number };
    getCarrierResourceStats(): { maxReceiveTasks: number; maxSendTasks: number; maxHandshakeTasks: number; gapAcks: number; retransmits: number };
    channelByLabel(label: string): RTCDataChannel | undefined;`)
  }
  return source
}

const script = fileURLToPath(import.meta.url)
if (process.argv[1] && resolve(process.argv[1]) === script) {
  const check = process.argv[2] === '--check'
  if (process.argv.length !== (check ? 3 : 2)) throw new Error('Usage: node patch-werift.mjs [--check]')
  const require = createRequire(import.meta.url), root = resolve(dirname(require.resolve('werift')), '../../..')
  const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'))
  if (pkg.name !== 'werift' || pkg.version !== '0.27.0') throw new Error('werift resource patch requires exact version 0.27.0')
  const manifest = JSON.parse(await readFile(new URL('./werift-resource-patch.json', import.meta.url), 'utf8'))
  const digest = bytes => createHash('sha256').update(bytes).digest('hex'), changes = []
  for (const [path, original] of Object.entries(upstream)) {
    const expected = manifest[path]
    if (!expected || expected.original !== original || !/^[a-f0-9]{64}$/.test(expected.patched)) throw new Error('Invalid werift patch manifest')
    const current = await readFile(join(root, path), 'utf8'), hash = digest(current)
    if (hash === expected.patched) continue
    if (hash !== original || check) throw new Error(`Unrecognized/unprepared werift resource patch: ${path}`)
    const next = transform(path, current)
    if (digest(next) !== expected.patched) throw new Error(`werift patch output digest mismatch: ${path}`)
    changes.push([join(root, path), next])
  }
  // Validate every file first. An unknown hash never causes a partial rewrite.
  for (const [path, bytes] of changes) await writeFile(path, bytes)
  console.log(check ? 'werift 0.27.0 resource patch verified' : 'werift 0.27.0 resource patch prepared')
}
