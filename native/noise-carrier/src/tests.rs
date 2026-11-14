use super::*;
use std::sync::LazyLock;

static SERIAL: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));
const INIT_STATIC: &str = "e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1";
const RESP_STATIC: &str = "4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893";
const RESP_PUBLIC: &str = "31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62";
const PROLOGUE: &str = "4a6f686e2047616c74";
// Public data from the fixed snow 0.10.0 interoperability fixture, whose complete
// SHA256 is 3bde7c09a6f349ee11c825c50fcc02649f8f02a47c857a459206b357f9386cae.
const MESSAGES: [(&str, &str); 6] = [
    ("4c756477696720766f6e204d69736573", "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7"),
    ("4d757272617920526f746862617264", "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f1448088435361e70b2ed446e6c9ec387d1d6b3b840f194e373979d241b203c4acafccf5"),
    ("462e20412e20486179656b", "050e9f3c8fac16b68dbce8f8c4bfbf6617c897f9ada4aa29aa19c8"),
    ("4361726c204d656e676572", "344233a6cabb7141d80f3da2fedc311d9646bbb0f505afe403a667"),
    ("4a65616e2d426170746973746520536179", "62cdeeb172ad7ade7aa7d9e069da5790f12331bfa00177787a1d0810c67dc3b2b4"),
    ("457567656e2042f6686d20766f6e2042617765726b", "029bead1b40992327044d409d9a1f3ad8f36c3c452775d557e18bbeb2e8dfcead32d514024"),
];
fn unhex(value: &str) -> Vec<u8> { hex::decode(value).unwrap() }
fn install(state: snow::HandshakeState) -> u32 {
    let mut slots = registry().lock().unwrap();
    let (index, slot) = slots.iter_mut().enumerate().find(|(_, slot)| slot.session.is_none()).unwrap();
    slot.generation += 1;
    slot.session = Some(Arc::new(Mutex::new(Engine::Handshake(Box::new(state)))));
    (slot.generation << 9) | index as u32 + 1
}
fn pair() -> (u32, u32) {
    let (a_secret, _) = generate_static().unwrap();
    let (b_secret, b_public) = generate_static().unwrap();
    let a = create(true, a_secret.as_ref(), Some(&b_public), b"production-random").unwrap();
    let b = create(false, b_secret.as_ref(), None, b"production-random").unwrap();
    let mut wire = [0u8; MAX_RECORD];
    let mut clear = [0u8; MAX_RECORD];
    let n = record(a, Operation::HandshakeWrite, &[], &mut wire).unwrap();
    assert_eq!(n, 96);
    assert_eq!(record(b, Operation::HandshakeRead, &wire[..n], &mut clear), Ok(0));
    let n = record(b, Operation::HandshakeWrite, &[], &mut wire).unwrap();
    assert_eq!(n, 48);
    assert_eq!(record(a, Operation::HandshakeRead, &wire[..n], &mut clear), Ok(0));
    assert_eq!(finish(a).unwrap().handshake_hash, finish(b).unwrap().handshake_hash);
    (a, b)
}

#[test]
fn known_answer_complete_wire_and_full_hash() {
    let _serial = SERIAL.lock().unwrap();
    let si = unhex(INIT_STATIC); let sr = unhex(RESP_STATIC); let rp = unhex(RESP_PUBLIC);
    let ei = unhex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a");
    let er = unhex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b");
    let prologue = unhex(PROLOGUE);
    let a = install(snow::Builder::new(SUITE.parse().unwrap()).local_private_key(&si).unwrap()
        .remote_public_key(&rp).unwrap().prologue(&prologue).unwrap()
        .fixed_ephemeral_key_for_testing_only(&ei).build_initiator().unwrap());
    let b = install(snow::Builder::new(SUITE.parse().unwrap()).local_private_key(&sr).unwrap()
        .prologue(&prologue).unwrap().fixed_ephemeral_key_for_testing_only(&er).build_responder().unwrap());
    let mut wire = [0u8; MAX_RECORD]; let mut clear = [0u8; MAX_RECORD];
    for (index, (payload, ciphertext)) in MESSAGES.iter().enumerate() {
        let payload = unhex(payload); let ciphertext = unhex(ciphertext);
        let (sender, receiver) = if index % 2 == 0 { (a, b) } else { (b, a) };
        let write = if index < 2 { Operation::HandshakeWrite } else { Operation::Encrypt };
        let read = if index < 2 { Operation::HandshakeRead } else { Operation::Decrypt };
        let n = record(sender, write, &payload, &mut wire).unwrap();
        assert_eq!(&wire[..n], ciphertext);
        let n = record(receiver, read, &wire[..n], &mut clear).unwrap();
        assert_eq!(&clear[..n], payload);
        if index == 1 {
            let ai = finish(a).unwrap(); let bi = finish(b).unwrap();
            assert_eq!(ai.remote_static.as_slice(), rp);
            let expected = unhex("0b0f68fb0c27e03ce9b97565995ed4838cc0581b762ef72b062f6a546419fad7");
            assert_eq!(ai.handshake_hash.as_slice(), expected);
            assert_eq!(bi.handshake_hash, ai.handshake_hash);
        }
    }
    close(a).unwrap(); close(b).unwrap();
}

#[test]
fn production_maximum_bidirectional_rekey_replay_and_zeroization() {
    let _serial = SERIAL.lock().unwrap();
    let (a, b) = pair();
    let mut wire = [0u8; MAX_RECORD]; let mut clear = [0u8; MAX_RECORD];
    let payload = [0x5au8; MAX_PLAINTEXT];
    for _ in 0..3 {
        let n = record(a, Operation::Encrypt, &payload, &mut wire).unwrap();
        assert_eq!(n, MAX_RECORD);
        assert_eq!(record(b, Operation::Decrypt, &wire[..n], &mut clear), Ok(MAX_PLAINTEXT));
        assert_eq!(&clear[..MAX_PLAINTEXT], payload);
        rekey(a, true).unwrap(); rekey(b, false).unwrap();
        let n = record(b, Operation::Encrypt, b"reverse", &mut wire).unwrap();
        assert_eq!(record(a, Operation::Decrypt, &wire[..n], &mut clear), Ok(7));
        rekey(b, true).unwrap(); rekey(a, false).unwrap();
    }
    let n = record(a, Operation::Encrypt, b"one-use", &mut wire).unwrap();
    assert_eq!(record(b, Operation::Decrypt, &wire[..n], &mut clear), Ok(7));
    clear.fill(0xaa);
    assert_eq!(record(b, Operation::Decrypt, &wire[..n], &mut clear), Err("e2e/auth"));
    assert!(clear.iter().all(|byte| *byte == 0));
    assert_eq!(rekey(b, false), Err("e2e/closed"));
    close(a).unwrap(); close(b).unwrap(); close(b).unwrap();
}

#[test]
fn tag_truncation_bounds_and_closed_generation() {
    let _serial = SERIAL.lock().unwrap();
    for kind in 0..3 {
        let (a, b) = pair(); let mut wire = [0u8; MAX_RECORD]; let mut clear = [0xaau8; MAX_RECORD];
        let n = record(a, Operation::Encrypt, b"authenticated", &mut wire).unwrap();
        let length = match kind { 0 => { wire[n-1] ^= 1; n }, 1 => n-1, _ => 15 };
        assert!(record(b, Operation::Decrypt, &wire[..length], &mut clear).is_err());
        assert!(clear.iter().all(|byte| *byte == 0));
        close(a).unwrap(); close(b).unwrap();
    }
    let (a, b) = pair(); let mut output = [0xaau8; MAX_RECORD];
    assert_eq!(record(a, Operation::Encrypt, &[0u8; MAX_PLAINTEXT + 1], &mut output), Err("e2e/bounds"));
    assert!(output.iter().all(|byte| *byte == 0));
    close(a).unwrap(); close(b).unwrap();
    let (new, peer) = pair(); assert_ne!(a, new);
    assert_eq!(record(a, Operation::Encrypt, b"stale", &mut output), Err("e2e/closed"));
    close(a).unwrap();
    assert!(record(new, Operation::Encrypt, b"alive", &mut output).is_ok());
    close(new).unwrap(); close(peer).unwrap();
}

#[test]
fn session_budget_and_close_race_are_bounded() {
    let _serial = SERIAL.lock().unwrap();
    let (secret, _) = generate_static().unwrap();
    let sessions: Vec<_> = (0..MAX_SESSIONS).map(|_| create(false, secret.as_ref(), None, &[]).unwrap()).collect();
    assert_eq!(create(false, secret.as_ref(), None, &[]), Err("e2e/session-budget"));
    for session in sessions { close(session).unwrap(); }
    let (a, b) = pair();
    let workers: Vec<_> = (0..16).map(|_| std::thread::spawn(move || {
        let mut output = [0u8; 64];
        for _ in 0..128 { let _ = record(a, Operation::Encrypt, b"serialized", &mut output); }
        close(a).unwrap();
    })).collect();
    for worker in workers { worker.join().unwrap(); }
    assert_eq!(rekey(a, true), Err("e2e/closed")); close(b).unwrap();
}

#[test]
fn wrong_key_handshake_failure_and_nonce_exhaustion_retire_state() {
    let _serial = SERIAL.lock().unwrap();
    let (secret, public) = generate_static().unwrap();
    let (other, _) = generate_static().unwrap();
    let a = create(true, secret.as_ref(), Some(&public), &[]).unwrap();
    let b = create(false, other.as_ref(), None, &[]).unwrap();
    let mut wire = [0u8; MAX_RECORD]; let mut clear = [0xaau8; MAX_RECORD];
    let n = record(a, Operation::HandshakeWrite, &[], &mut wire).unwrap();
    assert_eq!(record(b, Operation::HandshakeRead, &wire[..n], &mut clear), Err("e2e/handshake"));
    assert!(clear.iter().all(|byte| *byte == 0)); close(a).unwrap(); close(b).unwrap();
    let (a, b) = pair();
    let n = record(a, Operation::Encrypt, b"last", &mut wire).unwrap();
    let session = lookup(b).unwrap();
    if let Engine::Transport(state) = &mut *session.lock().unwrap() { state.set_receiving_nonce(u64::MAX); }
    assert!(record(b, Operation::Decrypt, &wire[..n], &mut clear).is_err());
    assert!(clear.iter().all(|byte| *byte == 0));
    close(a).unwrap(); close(b).unwrap();
}

#[test]
fn million_record_rekey_preserves_both_nonces() {
    let _serial = SERIAL.lock().unwrap();
    let (a, b) = pair();
    let mut wire = [0u8; 16];
    let mut clear = [];
    for _ in 0..(1 << 20) {
        assert_eq!(record(a, Operation::Encrypt, &[], &mut wire), Ok(16));
        assert_eq!(record(b, Operation::Decrypt, &wire, &mut clear), Ok(0));
    }
    rekey(a, true).unwrap(); rekey(b, false).unwrap();
    let sender = lookup(a).unwrap(); let receiver = lookup(b).unwrap();
    if let Engine::Transport(state) = &*sender.lock().unwrap() { assert_eq!(state.sending_nonce(), 1 << 20); }
    if let Engine::Transport(state) = &*receiver.lock().unwrap() { assert_eq!(state.receiving_nonce(), 1 << 20); }
    assert_eq!(record(a, Operation::Encrypt, &[], &mut wire), Ok(16));
    assert_eq!(record(b, Operation::Decrypt, &wire, &mut clear), Ok(0));
    close(a).unwrap(); close(b).unwrap();
}
