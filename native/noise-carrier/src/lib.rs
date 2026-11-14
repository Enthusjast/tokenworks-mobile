use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, LazyLock, Mutex};
use zeroize::{Zeroize, Zeroizing};

#[cfg(feature = "jni")]
mod jni;
#[cfg(feature = "node")]
mod node;
#[cfg(test)]
mod tests;

pub const SUITE: &str = "Noise_IK_25519_ChaChaPoly_SHA256";
pub const MAX_RECORD: usize = 65535;
pub const MAX_PLAINTEXT: usize = MAX_RECORD - 16;
const MAX_SESSIONS: usize = 256;
const MAX_GENERATION: u32 = (1 << 22) - 1;
pub type Result<T, E = &'static str> = std::result::Result<T, E>;

pub struct Binding {
    pub remote_static: [u8; 32],
    pub handshake_hash: [u8; 32],
}

enum Engine {
    Handshake(Box<snow::HandshakeState>),
    Transport(Box<snow::TransportState>),
    Closed,
}

struct Slot {
    generation: u32,
    session: Option<Arc<Mutex<Engine>>>,
}

static REGISTRY: LazyLock<Mutex<[Slot; MAX_SESSIONS]>> = LazyLock::new(||
    Mutex::new(std::array::from_fn(|_| Slot { generation: 0, session: None })));

fn registry() -> &'static Mutex<[Slot; MAX_SESSIONS]> {
    &REGISTRY
}
pub fn guarded<T>(operation: impl FnOnce() -> Result<T>) -> Result<T> {
    catch_unwind(AssertUnwindSafe(operation)).unwrap_or(Err("e2e/panic"))
}

pub fn generate_static() -> Result<(Zeroizing<[u8; 32]>, [u8; 32])> {
    guarded(|| {
        let mut pair = snow::Builder::new(SUITE.parse().map_err(|_| "e2e/suite")?)
            .generate_keypair().map_err(|_| "e2e/random")?;
        let private = Zeroizing::new(<[u8; 32]>::try_from(pair.private.as_slice()).map_err(|_| "e2e/key")?);
        pair.private.zeroize();
        let public = pair.public.as_slice().try_into().map_err(|_| "e2e/key")?;
        Ok((private, public))
    })
}

pub fn create(initiator: bool, local: &[u8], remote: Option<&[u8]>, prologue: &[u8]) -> Result<u32> {
    guarded(|| {
        if local.len() != 32 || remote.is_some_and(|key| key.len() != 32)
            || initiator != remote.is_some() || prologue.len() > 4096 {
            return Err("e2e/argument");
        }
        // Reserve while constructing: the engine budget includes in-flight handshakes.
        let mut slots = registry().lock().map_err(|_| "e2e/registry")?;
        let (index, slot) = slots.iter_mut().enumerate()
            .find(|(_, slot)| slot.session.is_none() && slot.generation < MAX_GENERATION)
            .ok_or("e2e/session-budget")?;
        let mut builder = snow::Builder::new(SUITE.parse().map_err(|_| "e2e/suite")?)
            .local_private_key(local).map_err(|_| "e2e/key")?
            .prologue(prologue).map_err(|_| "e2e/argument")?;
        if let Some(key) = remote { builder = builder.remote_public_key(key).map_err(|_| "e2e/key")?; }
        let handshake = if initiator { builder.build_initiator() } else { builder.build_responder() }
            .map_err(|_| "e2e/handshake")?;
        slot.generation += 1;
        slot.session = Some(Arc::new(Mutex::new(Engine::Handshake(Box::new(handshake)))));
        Ok((slot.generation << 9) | (index as u32 + 1))
    })
}

fn lookup(handle: u32) -> Result<Arc<Mutex<Engine>>> {
    let index = (handle & 511).checked_sub(1).ok_or("e2e/closed")? as usize;
    let slots = registry().lock().map_err(|_| "e2e/registry")?;
    let slot = slots.get(index).ok_or("e2e/closed")?;
    if handle >> 9 != slot.generation { return Err("e2e/closed"); }
    slot.session.clone().ok_or("e2e/closed")
}

fn remove(handle: u32) {
    if let Ok(mut slots) = registry().lock() {
        if let Some(slot) = (handle & 511).checked_sub(1).and_then(|i| slots.get_mut(i as usize)) {
            if slot.generation == handle >> 9 { slot.session = None; }
        }
    }
}

fn with_engine<T>(handle: u32, operation: impl FnOnce(&mut Engine) -> Result<T>) -> Result<T> {
    let session = lookup(handle)?;
    let mut engine = session.lock().map_err(|_| "e2e/closed")?;
    if matches!(*engine, Engine::Closed) { return Err("e2e/closed"); }
    let result = guarded(|| operation(&mut engine));
    if result.is_err() {
        *engine = Engine::Closed;
        remove(handle);
    }
    result
}

#[derive(Clone, Copy)]
pub enum Operation { HandshakeWrite, HandshakeRead, Encrypt, Decrypt }

pub fn record(handle: u32, operation: Operation, input: &[u8], output: &mut [u8]) -> Result<usize> {
    let result = with_engine(handle, |engine| {
        if input.len() > MAX_RECORD || output.len() > MAX_RECORD { return Err("e2e/bounds"); }
        match (operation, engine) {
            (Operation::HandshakeWrite, Engine::Handshake(state)) => {
                let overhead = if state.is_initiator() { 96 } else { 48 };
                if input.len() > MAX_RECORD - overhead || output.len() < input.len() + overhead {
                    return Err("e2e/bounds");
                }
                state.write_message(input, output).map_err(|_| "e2e/handshake")
            }
            (Operation::HandshakeRead, Engine::Handshake(state)) => {
                let overhead = if state.is_initiator() { 48 } else { 96 };
                if input.len() < overhead || output.len() < input.len() - overhead {
                    return Err("e2e/bounds");
                }
                state.read_message(input, output).map_err(|_| "e2e/handshake")
            }
            (Operation::Encrypt, Engine::Transport(state)) => {
                if input.len() > MAX_PLAINTEXT || output.len() < input.len() + 16 { return Err("e2e/bounds"); }
                state.write_message(input, output).map_err(|_| "e2e/encrypt")
            }
            (Operation::Decrypt, Engine::Transport(state)) => {
                if input.len() < 16 || output.len() < input.len() - 16 { return Err("e2e/bounds"); }
                state.read_message(input, output).map_err(|_| "e2e/auth")
            }
            _ => Err("e2e/state"),
        }
    });
    if result.is_err() { output.zeroize(); }
    result
}

pub fn finish(handle: u32) -> Result<Binding> {
    with_engine(handle, |engine| {
        let Engine::Handshake(state) = engine else { return Err("e2e/state"); };
        if !state.is_handshake_finished() { return Err("e2e/state"); }
        let binding = Binding {
            remote_static: state.get_remote_static().ok_or("e2e/key")?.try_into().map_err(|_| "e2e/key")?,
            handshake_hash: state.get_handshake_hash().try_into().map_err(|_| "e2e/hash")?,
        };
        let Engine::Handshake(state) = std::mem::replace(engine, Engine::Closed) else { unreachable!() };
        *engine = Engine::Transport(Box::new(state.into_transport_mode().map_err(|_| "e2e/handshake")?));
        Ok(binding)
    })
}

pub fn rekey(handle: u32, send: bool) -> Result<()> {
    with_engine(handle, |engine| {
        let Engine::Transport(state) = engine else { return Err("e2e/state"); };
        if send { state.rekey_outgoing(); } else { state.rekey_incoming(); }
        Ok(())
    })
}

pub fn close(handle: u32) -> Result<()> {
    guarded(|| {
        if let Ok(session) = lookup(handle) {
            let mut engine = session.lock().unwrap_or_else(|error| error.into_inner());
            *engine = Engine::Closed;
            remove(handle);
        }
        Ok(())
    })
}
