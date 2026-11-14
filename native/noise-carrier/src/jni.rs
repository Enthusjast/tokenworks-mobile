use ::jni::{JNIEnv, objects::{JByteArray, JByteBuffer, JClass}, sys::{jboolean, jint, jlong}};
use zeroize::{Zeroize, Zeroizing};

fn fail(env: &mut JNIEnv, message: &'static str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn key(env: &JNIEnv, array: &JByteArray) -> crate::Result<Zeroizing<[u8; 32]>> {
    if env.get_array_length(array).map_err(|_| "e2e/argument")? != 32 { return Err("e2e/key"); }
    let mut result = Zeroizing::new([0u8; 32]);
    // JNI bytes and Rust bytes have identical representations; no secret Vec/String.
    let target = unsafe { std::slice::from_raw_parts_mut(result.as_mut_ptr().cast::<i8>(), 32) };
    env.get_byte_array_region(array, 0, target).map_err(|_| "e2e/argument")?;
    Ok(result)
}

fn put(env: &JNIEnv, output: &JByteArray, bytes: &[u8]) -> crate::Result<()> {
    if env.get_array_length(output).map_err(|_| "e2e/argument")? as usize != bytes.len() { return Err("e2e/bounds"); }
    let source = unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<i8>(), bytes.len()) };
    env.set_byte_array_region(output, 0, source).map_err(|_| "e2e/argument")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_generateStatic(
    mut env: JNIEnv, _class: JClass, private: JByteArray, public: JByteArray,
) {
    let result = crate::guarded(|| {
        let (secret, key) = crate::generate_static()?;
        put(&env, &private, secret.as_ref())?;
        put(&env, &public, &key)
    });
    if let Err(message) = result { fail(&mut env, message); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_create(
    mut env: JNIEnv, _class: JClass, initiator: jboolean, local: JByteArray, remote: JByteArray, prologue: JByteArray,
) -> jlong {
    let result = crate::guarded(|| {
        let local = key(&env, &local)?;
        let remote = if remote.is_null() { None } else { Some(key(&env, &remote)?) };
        let size = env.get_array_length(&prologue).map_err(|_| "e2e/argument")?;
        if !(0..=4096).contains(&size) { return Err("e2e/bounds"); }
        let mut context = [0u8; 4096];
        let bytes = unsafe { std::slice::from_raw_parts_mut(context.as_mut_ptr().cast::<i8>(), size as usize) };
        env.get_byte_array_region(&prologue, 0, bytes).map_err(|_| "e2e/argument")?;
        crate::create(initiator != 0, local.as_ref(), remote.as_ref().map(|key| key.as_slice()), &context[..size as usize])
    });
    match result { Ok(handle) => handle as jlong, Err(message) => { fail(&mut env, message); 0 } }
}

fn region(env: &JNIEnv, buffer: &JByteBuffer, offset: jint, length: jint) -> crate::Result<(*mut u8, usize)> {
    if offset < 0 || !(0..=65535).contains(&length) { return Err("e2e/bounds"); }
    let capacity = env.get_direct_buffer_capacity(buffer).map_err(|_| "e2e/direct-buffer")?;
    if (offset as usize).checked_add(length as usize).is_none_or(|end| end > capacity) { return Err("e2e/bounds"); }
    let base = env.get_direct_buffer_address(buffer).map_err(|_| "e2e/direct-buffer")?;
    Ok((unsafe { base.add(offset as usize) }, length as usize))
}

fn handle(value: jlong) -> crate::Result<u32> {
    u32::try_from(value).ok().filter(|value| *value != 0).ok_or("e2e/closed")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_record(
    mut env: JNIEnv, _class: JClass, raw_handle: jlong, operation: jint,
    input: JByteBuffer, input_offset: jint, input_length: jint,
    output: JByteBuffer, output_offset: jint, output_length: jint,
) -> jint {
    let mut writable: Option<(*mut u8, usize)> = None;
    let result = crate::guarded(|| {
        let (out, out_len) = region(&env, &output, output_offset, output_length)?;
        writable = Some((out, out_len));
        let (source, source_len) = region(&env, &input, input_offset, input_length)?;
        let h = handle(raw_handle)?;
        let operation = match operation {
            0 => crate::Operation::HandshakeWrite, 1 => crate::Operation::HandshakeRead,
            2 => crate::Operation::Encrypt, 3 => crate::Operation::Decrypt,
            _ => return Err("e2e/argument"),
        };
        if (source as usize) < (out as usize).saturating_add(out_len)
            && (out as usize) < (source as usize).saturating_add(source_len) { return Err("e2e/overlap"); }
        // Address/length and non-overlap are established before constructing either slice.
        let source = unsafe { std::slice::from_raw_parts(source, source_len) };
        let destination = unsafe { std::slice::from_raw_parts_mut(out, out_len) };
        crate::record(h, operation, source, destination)
    });
    match result {
        Ok(n) => n as jint,
        Err(message) => {
            if let Some((pointer, length)) = writable {
                unsafe { std::slice::from_raw_parts_mut(pointer, length) }.zeroize();
            }
            if let Ok(h) = handle(raw_handle) { let _ = crate::close(h); }
            fail(&mut env, message);
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_finish(
    mut env: JNIEnv, _class: JClass, raw_handle: jlong, remote: JByteArray, hash: JByteArray,
) {
    let result = crate::guarded(|| {
        let binding = crate::finish(handle(raw_handle)?)?;
        put(&env, &remote, &binding.remote_static)?;
        put(&env, &hash, &binding.handshake_hash)
    });
    if let Err(message) = result {
        if let Ok(h) = handle(raw_handle) { let _ = crate::close(h); }
        fail(&mut env, message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_rekey(
    mut env: JNIEnv, _class: JClass, raw_handle: jlong, send: jboolean,
) {
    let result = crate::guarded(|| crate::rekey(handle(raw_handle)?, send != 0));
    if let Err(message) = result { fail(&mut env, message); }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cn_edu_ustc_tokenworks_E2eNative_close(
    mut env: JNIEnv, _class: JClass, raw_handle: jlong,
) {
    let result = crate::guarded(|| {
        if let Ok(h) = handle(raw_handle) { crate::close(h)?; }
        Ok(())
    });
    if let Err(message) = result { fail(&mut env, message); }
}
