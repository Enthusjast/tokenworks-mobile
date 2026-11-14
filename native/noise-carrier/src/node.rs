use napi::{bindgen_prelude::*, Env, JsTypedArray, NapiRaw};
use napi_derive::napi;
use zeroize::Zeroize;

fn error(message: &'static str) -> napi::Error { napi::Error::from_reason(message) }

// Synchronous, borrowed VM storage: no persistent reference, Vec or record allocation.
struct View { pointer: *mut u8, length: usize }
impl View {
    fn new(env: &Env, value: &JsTypedArray) -> crate::Result<Self> {
        let mut kind = 0;
        let mut length = 0;
        let mut data = std::ptr::null_mut();
        let mut buffer = std::ptr::null_mut();
        let mut offset = 0;
        let status = unsafe { napi::sys::napi_get_typedarray_info(env.raw(), value.raw(),
            &mut kind, &mut length, &mut data, &mut buffer, &mut offset) };
        if status != napi::sys::Status::napi_ok || kind != napi::sys::TypedarrayType::uint8_array {
            return Err("e2e/argument");
        }
        let mut ordinary = false;
        let status = unsafe { napi::sys::napi_is_arraybuffer(env.raw(), buffer, &mut ordinary) };
        if status != napi::sys::Status::napi_ok || !ordinary { return Err("e2e/argument"); }
        if length != 0 && data.is_null() { return Err("e2e/argument"); }
        Ok(Self { pointer: if data.is_null() { std::ptr::NonNull::<u8>::dangling().as_ptr() } else { data.cast() }, length })
    }
    fn bytes(&self) -> &[u8] { unsafe { std::slice::from_raw_parts(self.pointer, self.length) } }
    fn writable(&mut self) -> &mut [u8] { unsafe { std::slice::from_raw_parts_mut(self.pointer, self.length) } }
}

#[napi(object)]
pub struct StaticKeys { pub private_key: Uint8Array, pub public_key: Uint8Array }
#[napi(object)]
pub struct HandshakeBinding { pub remote_static: Uint8Array, pub handshake_hash: Uint8Array }

#[napi]
pub fn generate_static() -> napi::Result<StaticKeys> {
    crate::guarded(|| {
        let (private, public) = crate::generate_static()?;
        Ok(StaticKeys { private_key: Uint8Array::new(private.to_vec()), public_key: Uint8Array::new(public.to_vec()) })
    }).map_err(error)
}

#[napi]
pub fn create(env: Env, initiator: bool, local: JsTypedArray, remote: Option<JsTypedArray>, prologue: JsTypedArray) -> napi::Result<u32> {
    crate::guarded(|| {
        let local = View::new(&env, &local)?;
        let remote = remote.as_ref().map(|value| View::new(&env, value)).transpose()?;
        let prologue = View::new(&env, &prologue)?;
        crate::create(initiator, local.bytes(), remote.as_ref().map(View::bytes), prologue.bytes())
    }).map_err(error)
}

#[napi]
pub fn record(env: Env, handle: u32, operation: u32, input: JsTypedArray, output: JsTypedArray) -> napi::Result<u32> {
    let mut output = match View::new(&env, &output) {
        Ok(output) => output,
        Err(message) => { let _ = crate::close(handle); return Err(error(message)); }
    };
    let result = crate::guarded(|| {
        let input = View::new(&env, &input)?;
        let operation = match operation {
            0 => crate::Operation::HandshakeWrite, 1 => crate::Operation::HandshakeRead,
            2 => crate::Operation::Encrypt, 3 => crate::Operation::Decrypt,
            _ => return Err("e2e/argument"),
        };
        let start = input.pointer as usize;
        let out = output.pointer as usize;
        if start < out.saturating_add(output.length) && out < start.saturating_add(input.length) {
            return Err("e2e/overlap");
        }
        crate::record(handle, operation, input.bytes(), output.writable())
    });
    if result.is_err() { output.writable().zeroize(); let _ = crate::close(handle); }
    result.map(|n| n as u32).map_err(error)
}

#[napi]
pub fn finish(handle: u32) -> napi::Result<HandshakeBinding> {
    crate::guarded(|| {
        let binding = crate::finish(handle)?;
        Ok(HandshakeBinding { remote_static: Uint8Array::new(binding.remote_static.to_vec()),
            handshake_hash: Uint8Array::new(binding.handshake_hash.to_vec()) })
    }).map_err(error)
}

#[napi]
pub fn rekey(handle: u32, send: bool) -> napi::Result<()> { crate::rekey(handle, send).map_err(error) }
#[napi]
pub fn close(handle: u32) -> napi::Result<()> { crate::close(handle).map_err(error) }
