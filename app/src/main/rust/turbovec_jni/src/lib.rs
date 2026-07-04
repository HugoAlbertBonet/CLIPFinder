use std::collections::HashSet;
use std::io::{Read, Write};
use std::panic;
use std::path::Path;
use std::time::Instant;

use jni::objects::{JClass, JFloatArray, JLongArray, JString};
use jni::sys::{jboolean, jfloatArray, jint, jlong, jlongArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use turbovec::IdMapIndex;
use turbovec::{codebook, encode, rotation};

const HIGHBIT_BLOCK: usize = 32;
const HIGHBIT_MAGIC: &[u8; 4] = b"HBVQ";
const HIGHBIT_VERSION: u32 = 1;

struct Int8Index {
    dim: usize,
    ids: Vec<u64>,
    codes: Vec<i8>,
    scales: Vec<f32>,
}

struct TurboVecIndex {
    index: IdMapIndex,
}

struct HighBitIndex {
    dim: usize,
    bits: usize,
    n_blocks: usize,
    ids: Vec<u64>,
    codes_u8: Vec<u8>,
    codes_u16: Vec<u16>,
    scales: Vec<f32>,
    rotation: Vec<f32>,
    centroids: Vec<f32>,
    tqplus_shift: Vec<f32>,
    inv_tqplus_scale: Vec<f32>,
}

fn read_float_array(env: &mut JNIEnv, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let len = env
        .get_array_length(array)
        .map_err(|e| format!("Could not read float array length: {e}"))? as usize;
    let mut out = vec![0f32; len];
    env.get_float_array_region(array, 0, &mut out)
        .map_err(|e| format!("Could not read float array region: {e}"))?;
    Ok(out)
}

fn read_long_array(env: &mut JNIEnv, array: &JLongArray) -> Result<Vec<i64>, String> {
    let len = env
        .get_array_length(array)
        .map_err(|e| format!("Could not read long array length: {e}"))? as usize;
    let mut out = vec![0i64; len];
    env.get_long_array_region(array, 0, &mut out)
        .map_err(|e| format!("Could not read long array region: {e}"))?;
    Ok(out)
}

fn read_jstring(env: &mut JNIEnv, value: jstring) -> Result<String, String> {
    if value.is_null() {
        return Err("Java string is null".to_string());
    }
    let jstr = unsafe { JString::from_raw(value) };
    env.get_string(&jstr)
        .map(|s| s.into())
        .map_err(|e| format!("Could not read Java string: {e}"))
}

fn read_optional_allowlist(
    env: &mut JNIEnv,
    allowlist: jlongArray,
) -> Result<Option<HashSet<u64>>, String> {
    if allowlist.is_null() {
        return Ok(None);
    }
    let array = unsafe { JLongArray::from_raw(allowlist) };
    let ids = read_long_array(env, &array)?;
    Ok(Some(ids.into_iter().map(|id| id as u64).collect()))
}

fn write_u32(writer: &mut impl Write, value: u32) -> Result<(), String> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(|e| format!("write failed: {e}"))
}

fn write_u64(writer: &mut impl Write, value: u64) -> Result<(), String> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(|e| format!("write failed: {e}"))
}

fn read_u32(reader: &mut impl Read) -> Result<u32, String> {
    let mut buf = [0u8; 4];
    reader
        .read_exact(&mut buf)
        .map_err(|e| format!("read failed: {e}"))?;
    Ok(u32::from_le_bytes(buf))
}

fn read_u64(reader: &mut impl Read) -> Result<u64, String> {
    let mut buf = [0u8; 8];
    reader
        .read_exact(&mut buf)
        .map_err(|e| format!("read failed: {e}"))?;
    Ok(u64::from_le_bytes(buf))
}

fn write_f32_slice(writer: &mut impl Write, values: &[f32]) -> Result<(), String> {
    write_u64(writer, values.len() as u64)?;
    for &value in values {
        writer
            .write_all(&value.to_bits().to_le_bytes())
            .map_err(|e| format!("write failed: {e}"))?;
    }
    Ok(())
}

fn read_f32_slice(reader: &mut impl Read) -> Result<Vec<f32>, String> {
    let len = read_u64(reader)? as usize;
    let mut out = Vec::with_capacity(len);
    for _ in 0..len {
        let mut buf = [0u8; 4];
        reader
            .read_exact(&mut buf)
            .map_err(|e| format!("read failed: {e}"))?;
        out.push(f32::from_bits(u32::from_le_bytes(buf)));
    }
    Ok(out)
}

fn build_highbit_index(
    vectors: &[f32],
    ids_i64: &[i64],
    dim: usize,
    bits: usize,
) -> Result<HighBitIndex, String> {
    if dim == 0 {
        return Err("dim must be positive".to_string());
    }
    if ![6, 8, 12].contains(&bits) {
        return Err("faithful high-bit TurboQuant scorer currently supports 6, 8, and 12 bits".to_string());
    }
    if vectors.len() % dim != 0 {
        return Err("vector buffer length is not a multiple of dim".to_string());
    }
    let n = vectors.len() / dim;
    if ids_i64.len() != n {
        return Err("ids length does not match vector count".to_string());
    }

    let rotation = rotation::make_rotation_matrix(dim);
    let (boundaries, centroids) = codebook::codebook(bits, dim);
    let mut codes_u8 = Vec::new();
    let mut codes_u16 = Vec::new();
    let scales: Vec<f32>;
    let tqplus_shift: Vec<f32>;
    let tqplus_scale: Vec<f32>;
    let n_blocks: usize;

    if bits <= 8 {
        let (packed, encoded_scales, shift, scale_tq) = encode::encode(
            vectors,
            n,
            dim,
            &rotation,
            &boundaries,
            &centroids,
            bits,
            None,
        );
        scales = encoded_scales;
        tqplus_shift = shift;
        tqplus_scale = scale_tq;
        n_blocks = (n + HIGHBIT_BLOCK - 1) / HIGHBIT_BLOCK;
        let blocked_len = n_blocks * dim * HIGHBIT_BLOCK;
        codes_u8 = vec![0u8; blocked_len];
        let bytes_per_plane = dim / 8;
        let bytes_per_row = bits * bytes_per_plane;
        for row in 0..n {
            let block = row / HIGHBIT_BLOCK;
            let lane = row % HIGHBIT_BLOCK;
            let row_offset = row * bytes_per_row;
            for d in 0..dim {
                let byte_in_plane = d / 8;
                let bit_in_byte = 7 - (d % 8);
                let mask = 1u8 << bit_in_byte;
                let mut code = 0u8;
                for p in 0..bits {
                    let plane_byte = packed[row_offset + p * bytes_per_plane + byte_in_plane];
                    if plane_byte & mask != 0 {
                        code |= 1 << p;
                    }
                }
                codes_u8[block * dim * HIGHBIT_BLOCK + d * HIGHBIT_BLOCK + lane] = code;
            }
        }
    } else {
        let (boundaries8, centroids8) = codebook::codebook(8, dim);
        let (_, _, shift, scale_tq) = encode::encode(
            vectors,
            n,
            dim,
            &rotation,
            &boundaries8,
            &centroids8,
            8,
            None,
        );
        tqplus_shift = shift;
        tqplus_scale = scale_tq;
        let (encoded_codes, encoded_scales, blocks) = encode_highbit_u16(
            vectors,
            n,
            dim,
            &rotation,
            &boundaries,
            &centroids,
            &tqplus_shift,
            &tqplus_scale,
        );
        codes_u16 = encoded_codes;
        scales = encoded_scales;
        n_blocks = blocks;
    }

    let inv_tqplus_scale: Vec<f32> = tqplus_scale.iter().map(|s| 1.0 / s).collect();
    Ok(HighBitIndex {
        dim,
        bits,
        n_blocks,
        ids: ids_i64.iter().map(|id| *id as u64).collect(),
        codes_u8,
        codes_u16,
        scales,
        rotation,
        centroids,
        tqplus_shift,
        inv_tqplus_scale,
    })
}

fn write_highbit_index(index: &HighBitIndex, path: &Path) -> Result<(), String> {
    let mut file =
        std::fs::File::create(path).map_err(|e| format!("Could not create high-bit index file: {e}"))?;
    file.write_all(HIGHBIT_MAGIC)
        .map_err(|e| format!("Could not write high-bit index header: {e}"))?;
    write_u32(&mut file, HIGHBIT_VERSION)?;
    write_u32(&mut file, index.dim as u32)?;
    write_u32(&mut file, index.bits as u32)?;
    write_u32(&mut file, index.n_blocks as u32)?;
    write_u64(&mut file, index.ids.len() as u64)?;
    for &id in &index.ids {
        write_u64(&mut file, id)?;
    }
    write_u64(&mut file, index.codes_u8.len() as u64)?;
    file.write_all(&index.codes_u8)
        .map_err(|e| format!("Could not write high-bit u8 codes: {e}"))?;
    write_u64(&mut file, index.codes_u16.len() as u64)?;
    for &code in &index.codes_u16 {
        file.write_all(&code.to_le_bytes())
            .map_err(|e| format!("Could not write high-bit u16 codes: {e}"))?;
    }
    write_f32_slice(&mut file, &index.scales)?;
    write_f32_slice(&mut file, &index.rotation)?;
    write_f32_slice(&mut file, &index.centroids)?;
    write_f32_slice(&mut file, &index.tqplus_shift)?;
    write_f32_slice(&mut file, &index.inv_tqplus_scale)?;
    Ok(())
}

fn load_highbit_index(path: &Path) -> Result<HighBitIndex, String> {
    let mut file =
        std::fs::File::open(path).map_err(|e| format!("Could not open high-bit index file: {e}"))?;
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic)
        .map_err(|e| format!("Could not read high-bit index header: {e}"))?;
    if &magic != HIGHBIT_MAGIC {
        return Err("high-bit index file has invalid magic".to_string());
    }
    let version = read_u32(&mut file)?;
    if version != HIGHBIT_VERSION {
        return Err(format!("unsupported high-bit index version {version}"));
    }
    let dim = read_u32(&mut file)? as usize;
    let bits = read_u32(&mut file)? as usize;
    let n_blocks = read_u32(&mut file)? as usize;
    let id_count = read_u64(&mut file)? as usize;
    let mut ids = Vec::with_capacity(id_count);
    for _ in 0..id_count {
        ids.push(read_u64(&mut file)?);
    }
    let codes_u8_len = read_u64(&mut file)? as usize;
    let mut codes_u8 = vec![0u8; codes_u8_len];
    if codes_u8_len > 0 {
        file.read_exact(&mut codes_u8)
            .map_err(|e| format!("Could not read high-bit u8 codes: {e}"))?;
    }
    let codes_u16_len = read_u64(&mut file)? as usize;
    let mut codes_u16 = Vec::with_capacity(codes_u16_len);
    for _ in 0..codes_u16_len {
        let mut buf = [0u8; 2];
        file.read_exact(&mut buf)
            .map_err(|e| format!("Could not read high-bit u16 codes: {e}"))?;
        codes_u16.push(u16::from_le_bytes(buf));
    }
    Ok(HighBitIndex {
        dim,
        bits,
        n_blocks,
        ids,
        codes_u8,
        codes_u16,
        scales: read_f32_slice(&mut file)?,
        rotation: read_f32_slice(&mut file)?,
        centroids: read_f32_slice(&mut file)?,
        tqplus_shift: read_f32_slice(&mut file)?,
        inv_tqplus_scale: read_f32_slice(&mut file)?,
    })
}

fn highbit_search_impl(
    index: &HighBitIndex,
    query: &[f32],
    k: usize,
    allowlist: Option<&HashSet<u64>>,
) -> Result<Vec<i64>, String> {
    let search_start = Instant::now();
    let limit = (k.min(index.ids.len())).max(1);
    let (query_lut, bias, lut_scale, levels) = faithful_query_lut(index, query);
    let mut scores = Vec::<f32>::with_capacity(limit);
    let mut rows = Vec::<usize>::with_capacity(limit);
    let mut min_idx = 0usize;
    let mut min_score = f32::INFINITY;

    for block in 0..index.n_blocks {
        let block_start = block * HIGHBIT_BLOCK;
        let block_len = (index.ids.len() - block_start).min(HIGHBIT_BLOCK);
        let code_start = block * index.dim * HIGHBIT_BLOCK;
        let code_end = code_start + index.dim * HIGHBIT_BLOCK;

        if index.bits <= 8 {
            let mut block_scores = [0u32; HIGHBIT_BLOCK];
            score_turbo_u8_block(
                &index.codes_u8[code_start..code_end],
                &query_lut,
                levels,
                index.dim,
                &mut block_scores,
            );
            for lane in 0..block_len {
                let row = block_start + lane;
                let id = index.ids[row];
                if let Some(set) = allowlist {
                    if !set.contains(&id) {
                        continue;
                    }
                }
                let score = (bias + lut_scale * block_scores[lane] as f32) * index.scales[row];
                offer_top(
                    &mut scores,
                    &mut rows,
                    score,
                    row,
                    limit,
                    &mut min_idx,
                    &mut min_score,
                );
            }
        } else {
            let mut block_scores = [0u32; HIGHBIT_BLOCK];
            score_turbo_u16_block(
                &index.codes_u16[code_start..code_end],
                &query_lut,
                levels,
                index.dim,
                &mut block_scores,
            );
            for lane in 0..block_len {
                let row = block_start + lane;
                let id = index.ids[row];
                if let Some(set) = allowlist {
                    if !set.contains(&id) {
                        continue;
                    }
                }
                let score = (bias + lut_scale * block_scores[lane] as f32) * index.scales[row];
                offer_top(
                    &mut scores,
                    &mut rows,
                    score,
                    row,
                    limit,
                    &mut min_idx,
                    &mut min_score,
                );
            }
        }
    }

    let elapsed_ms = search_start.elapsed().as_millis().min(i64::MAX as u128) as i64;
    let mut order: Vec<usize> = (0..scores.len()).collect();
    order.sort_by(|&a, &b| scores[b].partial_cmp(&scores[a]).unwrap_or(std::cmp::Ordering::Equal));
    let mut out = Vec::<i64>::with_capacity(2 + order.len() * 2);
    out.push(elapsed_ms);
    out.push(order.len() as i64);
    for idx in order {
        out.push(index.ids[rows[idx]] as i64);
        out.push(scores[idx].to_bits() as i64);
    }
    Ok(out)
}

fn empty_long_array(env: &mut JNIEnv) -> jlongArray {
    env.new_long_array(0)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn throw(env: &mut JNIEnv, message: impl AsRef<str>) -> jlongArray {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
    empty_long_array(env)
}

fn throw_long(env: &mut JNIEnv, message: impl AsRef<str>) -> jlong {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
    0
}

fn quantize_symmetric_i8(values: &[f32]) -> (Vec<i8>, f32) {
    let max_abs = values
        .iter()
        .fold(0.0f32, |acc, value| acc.max(value.abs()))
        .max(1e-12);
    let scale = max_abs / 127.0;
    let codes = values
        .iter()
        .map(|value| (value / scale).round().clamp(-127.0, 127.0) as i8)
        .collect();
    (codes, scale)
}

#[cfg(target_arch = "aarch64")]
#[inline(always)]
fn dot_i8_codes(codes: &[i8], query: &[i8]) -> i32 {
    use std::arch::aarch64::*;

    debug_assert_eq!(codes.len(), query.len());
    let len = codes.len();
    let mut i = 0usize;
    let mut acc = unsafe { vdupq_n_s32(0) };
    while i + 16 <= len {
        unsafe {
            let a = vld1q_s8(codes.as_ptr().add(i));
            let b = vld1q_s8(query.as_ptr().add(i));
            let lo = vmull_s8(vget_low_s8(a), vget_low_s8(b));
            let hi = vmull_s8(vget_high_s8(a), vget_high_s8(b));
            acc = vpadalq_s16(acc, lo);
            acc = vpadalq_s16(acc, hi);
        }
        i += 16;
    }

    let mut sum = unsafe { vaddvq_s32(acc) };
    while i < len {
        sum += (codes[i] as i32) * (query[i] as i32);
        i += 1;
    }
    sum
}

#[cfg(not(target_arch = "aarch64"))]
#[inline(always)]
fn dot_i8_codes(codes: &[i8], query: &[i8]) -> i32 {
    codes
        .iter()
        .zip(query.iter())
        .map(|(&a, &b)| (a as i32) * (b as i32))
        .sum()
}

#[inline(always)]
fn score_turbo_u8_block(
    codes: &[u8],
    lut: &[u8],
    levels: usize,
    dim: usize,
    out: &mut [u32; HIGHBIT_BLOCK],
) {
    out.fill(0);
    unsafe {
        let codes_ptr = codes.as_ptr();
        let lut_ptr = lut.as_ptr();
        let out_ptr = out.as_mut_ptr();
        for d in 0..dim {
            let lut_row = lut_ptr.add(d * levels);
            let code_row = codes_ptr.add(d * HIGHBIT_BLOCK);
            for lane in 0..HIGHBIT_BLOCK {
                *out_ptr.add(lane) +=
                    *lut_row.add(*code_row.add(lane) as usize) as u32;
            }
        }
    }
}

#[inline(always)]
fn score_turbo_u16_block(
    codes: &[u16],
    lut: &[u8],
    levels: usize,
    dim: usize,
    out: &mut [u32; HIGHBIT_BLOCK],
) {
    out.fill(0);
    for d in 0..dim {
        let base = d * HIGHBIT_BLOCK;
        let lut_base = d * levels;
        for lane in 0..HIGHBIT_BLOCK {
            out[lane] += lut[lut_base + codes[base + lane] as usize] as u32;
        }
    }
}

fn faithful_query_lut(index: &HighBitIndex, query: &[f32]) -> (Vec<u8>, f32, f32, usize) {
    let dim = index.dim;
    let levels = 1usize << index.bits;
    let mut bias = 0.0f32;
    let mut min_value = f32::INFINITY;
    let mut max_value = f32::NEG_INFINITY;
    let first_centroid = index.centroids[0];
    let last_centroid = index.centroids[levels - 1];
    let mut query_lut = vec![0u8; dim * levels];
    let mut q_calib = vec![0.0f32; dim];

    // Fuse rotation + TQ+ calibration + LUT range bounds in one pass.
    for d in 0..dim {
        let row = &index.rotation[d * dim..(d + 1) * dim];
        let mut q_rot_d = 0.0f32;
        let mut j = 0usize;
        while j + 4 <= dim {
            q_rot_d += query[j] * row[j]
                + query[j + 1] * row[j + 1]
                + query[j + 2] * row[j + 2]
                + query[j + 3] * row[j + 3];
            j += 4;
        }
        while j < dim {
            q_rot_d += query[j] * row[j];
            j += 1;
        }

        let q = if index.tqplus_shift.is_empty() {
            q_rot_d
        } else {
            bias -= q_rot_d * index.tqplus_shift[d];
            q_rot_d * index.inv_tqplus_scale[d]
        };
        q_calib[d] = q;
        let a = q * first_centroid;
        let b = q * last_centroid;
        min_value = min_value.min(a.min(b));
        max_value = max_value.max(a.max(b));
    }

    let span = (max_value - min_value).max(1e-12);
    let lut_scale = span / 255.0;
    let inv_lut_scale = 255.0 / span;
    let centroids = index.centroids.as_slice();

    for d in 0..dim {
        let q = q_calib[d];
        let base = d * levels;
        unsafe {
            let out = query_lut.as_mut_ptr().add(base);
            let cent = centroids.as_ptr();
            let mut code = 0usize;
            while code + 4 <= levels {
                let v0 = q * *cent.add(code);
                let v1 = q * *cent.add(code + 1);
                let v2 = q * *cent.add(code + 2);
                let v3 = q * *cent.add(code + 3);
                *out.add(code) = ((v0 - min_value) * inv_lut_scale)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                *out.add(code + 1) = ((v1 - min_value) * inv_lut_scale)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                *out.add(code + 2) = ((v2 - min_value) * inv_lut_scale)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                *out.add(code + 3) = ((v3 - min_value) * inv_lut_scale)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                code += 4;
            }
            while code < levels {
                let v = q * *cent.add(code);
                *out.add(code) = ((v - min_value) * inv_lut_scale)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                code += 1;
            }
        }
    }

    (
        query_lut,
        bias + dim as f32 * min_value,
        lut_scale,
        levels,
    )
}

#[inline(always)]
fn code_for_value(value: f32, boundaries: &[f32]) -> usize {
    let mut lo = 0usize;
    let mut hi = boundaries.len();
    while lo < hi {
        let mid = (lo + hi) / 2;
        if value > boundaries[mid] {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    lo
}

fn encode_highbit_u16(
    vectors: &[f32],
    n: usize,
    dim: usize,
    rotation: &[f32],
    boundaries: &[f32],
    centroids: &[f32],
    tqplus_shift: &[f32],
    tqplus_scale: &[f32],
) -> (Vec<u16>, Vec<f32>, usize) {
    let n_blocks = (n + HIGHBIT_BLOCK - 1) / HIGHBIT_BLOCK;
    let mut codes = vec![0u16; n_blocks * dim * HIGHBIT_BLOCK];
    let mut scales = vec![0.0f32; n];
    let inv_scale_tq: Vec<f32> = tqplus_scale.iter().map(|value| 1.0 / value).collect();
    let mut rotated = vec![0.0f32; dim];

    for row in 0..n {
        let start = row * dim;
        let vector = &vectors[start..start + dim];
        let mut norm_sq = 0.0f32;
        for &value in vector {
            norm_sq += value * value;
        }
        let norm = norm_sq.sqrt();
        let inv_norm = if norm > 1e-10 { 1.0 / norm } else { 0.0 };

        for d in 0..dim {
            let rotation_row = &rotation[d * dim..(d + 1) * dim];
            let mut sum = 0.0f32;
            for j in 0..dim {
                sum += vector[j] * inv_norm * rotation_row[j];
            }
            rotated[d] = sum;
        }

        let block = row / HIGHBIT_BLOCK;
        let lane = row % HIGHBIT_BLOCK;
        let block_offset = block * dim * HIGHBIT_BLOCK;
        let mut inner = 0.0f64;
        for d in 0..dim {
            let calibrated = (rotated[d] + tqplus_shift[d]) * tqplus_scale[d];
            let code = code_for_value(calibrated, boundaries);
            codes[block_offset + d * HIGHBIT_BLOCK + lane] = code as u16;
            let centroid_in_orig =
                (centroids[code] as f64) * (inv_scale_tq[d] as f64) - (tqplus_shift[d] as f64);
            inner += (rotated[d] as f64) * centroid_in_orig;
        }
        scales[row] = norm / (inner.max(1e-10) as f32);
    }

    (codes, scales, n_blocks)
}

#[inline(always)]
fn offer_top(
    scores: &mut Vec<f32>,
    rows: &mut Vec<usize>,
    score: f32,
    row: usize,
    limit: usize,
    min_idx: &mut usize,
    min_score: &mut f32,
) {
    if scores.len() < limit {
        scores.push(score);
        rows.push(row);
        if scores.len() == limit {
            *min_idx = 0;
            *min_score = scores[0];
            for i in 1..limit {
                if scores[i] < *min_score {
                    *min_score = scores[i];
                    *min_idx = i;
                }
            }
        }
    } else if score > *min_score {
        scores[*min_idx] = score;
        rows[*min_idx] = row;
        *min_idx = 0;
        *min_score = scores[0];
        for i in 1..limit {
            if scores[i] < *min_score {
                *min_score = scores[i];
                *min_idx = i;
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_createNative(
    mut env: JNIEnv,
    _class: JClass,
    vectors: jfloatArray,
    ids: jlongArray,
    dim: jint,
    bit_width: jint,
) -> jlong {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if dim <= 0 {
            return Err("dim must be positive".to_string());
        }
        if !(2..=4).contains(&bit_width) {
            return Err("turbovec supports only 2, 3, and 4 bit widths".to_string());
        }
        let vectors_array = unsafe { JFloatArray::from_raw(vectors) };
        let ids_array = unsafe { JLongArray::from_raw(ids) };
        let vectors = read_float_array(&mut env, &vectors_array)?;
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let dim = dim as usize;
        if vectors.len() % dim != 0 {
            return Err("vector buffer length is not a multiple of dim".to_string());
        }
        let n = vectors.len() / dim;
        if ids_i64.len() != n {
            return Err("ids length does not match vector count".to_string());
        }
        let ids_u64: Vec<u64> = ids_i64.into_iter().map(|id| id as u64).collect();
        let mut index = IdMapIndex::new(dim, bit_width as usize)
            .map_err(|e| format!("Could not create turbovec index: {e:?}"))?;
        index
            .add_with_ids(&vectors, &ids_u64)
            .map_err(|e| format!("Could not add vectors to turbovec index: {e:?}"))?;
        index.prepare();
        Ok(Box::into_raw(Box::new(TurboVecIndex { index })) as jlong)
    }));

    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => throw_long(&mut env, message),
        Err(_) => throw_long(&mut env, "Native turbovec index creation panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_searchNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: jfloatArray,
    k: jint,
    allowlist: jlongArray,
) -> jlongArray {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native turbovec index handle is null".to_string());
        }
        if k <= 0 {
            return Err("k must be positive".to_string());
        }
        let query_array = unsafe { JFloatArray::from_raw(query) };
        let query = read_float_array(&mut env, &query_array)?;
        let index = unsafe { &*(handle as *const TurboVecIndex) };
        if query.len() != index.index.dim() {
            return Err("query dimension does not match index dimension".to_string());
        }
        let allowlist_set = read_optional_allowlist(&mut env, allowlist)?;
        let search_start = Instant::now();
        let (scores, result_ids) = match allowlist_set {
            Some(set) if !set.is_empty() => {
                let ids: Vec<u64> = set.into_iter().collect();
                index.index.search_with_allowlist(&query, k as usize, Some(&ids))
            }
            _ => index.index.search(&query, k as usize),
        };
        let elapsed_ms = search_start.elapsed().as_millis().min(i64::MAX as u128) as i64;
        let result_count = scores.len().min(result_ids.len());
        let mut out = Vec::<i64>::with_capacity(2 + result_count * 2);
        out.push(elapsed_ms);
        out.push(result_count as i64);
        for i in 0..result_count {
            out.push(result_ids[i] as i64);
            out.push(scores[i].to_bits() as i64);
        }
        Ok(out)
    }));

    let out = match result {
        Ok(Ok(out)) => out,
        Ok(Err(message)) => return throw(&mut env, message),
        Err(_) => return throw(&mut env, "Native turbovec search panicked"),
    };

    match env.new_long_array(out.len() as i32) {
        Ok(array) => {
            if let Err(e) = env.set_long_array_region(&array, 0, &out) {
                return throw(&mut env, format!("Could not write turbovec result: {e}"));
            }
            array.into_raw()
        }
        Err(e) => throw(&mut env, format!("Could not allocate turbovec result: {e}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_closeNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut TurboVecIndex));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_writeNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: jstring,
) -> jboolean {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native turbovec index handle is null".to_string());
        }
        let path = read_jstring(&mut env, path)?;
        let index = unsafe { &*(handle as *const TurboVecIndex) };
        index
            .index
            .write(Path::new(&path))
            .map_err(|e| format!("Could not write turbovec index: {e}"))
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            JNI_FALSE
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Native turbovec write panicked");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_loadNative(
    mut env: JNIEnv,
    _class: JClass,
    path: jstring,
) -> jlong {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let path = read_jstring(&mut env, path)?;
        let mut index = IdMapIndex::load(Path::new(&path))
            .map_err(|e| format!("Could not load turbovec index: {e}"))?;
        index.prepare();
        Ok::<jlong, String>(Box::into_raw(Box::new(TurboVecIndex { index })) as jlong)
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => throw_long(&mut env, message),
        Err(_) => throw_long(&mut env, "Native turbovec load panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_addVectorsNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    vectors: jfloatArray,
    ids: jlongArray,
    dim: jint,
) -> jboolean {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native turbovec index handle is null".to_string());
        }
        let vectors_array = unsafe { JFloatArray::from_raw(vectors) };
        let ids_array = unsafe { JLongArray::from_raw(ids) };
        let vectors = read_float_array(&mut env, &vectors_array)?;
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let dim = dim as usize;
        if dim == 0 || vectors.len() % dim != 0 {
            return Err("vector buffer length is not a multiple of dim".to_string());
        }
        let ids_u64: Vec<u64> = ids_i64.into_iter().map(|id| id as u64).collect();
        let index = unsafe { &mut *(handle as *mut TurboVecIndex) };
        index
            .index
            .add_with_ids(&vectors, &ids_u64)
            .map_err(|e| format!("Could not add vectors to turbovec index: {e:?}"))?;
        index.index.prepare();
        Ok(())
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            JNI_FALSE
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Native turbovec add panicked");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_removeIdsNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    ids: jlongArray,
) -> jboolean {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native turbovec index handle is null".to_string());
        }
        let ids_array = unsafe { JLongArray::from_raw(ids) };
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let index = unsafe { &mut *(handle as *mut TurboVecIndex) };
        for id in ids_i64 {
            index.index.remove(id as u64);
        }
        index.index.prepare();
        Ok(())
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            JNI_FALSE
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Native turbovec remove panicked");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVecIndex_vectorCountNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let index = unsafe { &*(handle as *const TurboVecIndex) };
    index.index.len().min(i32::MAX as usize) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeTurboVec_searchNative(
    mut env: JNIEnv,
    _class: JClass,
    vectors: jfloatArray,
    ids: jlongArray,
    dim: jint,
    bit_width: jint,
    query: jfloatArray,
    k: jint,
) -> jlongArray {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if dim <= 0 || k <= 0 {
            return Err("dim and k must be positive".to_string());
        }
        if !(2..=4).contains(&bit_width) {
            return Err("turbovec supports only 2, 3, and 4 bit widths".to_string());
        }

        let vectors_array = unsafe { JFloatArray::from_raw(vectors) };
        let ids_array = unsafe { JLongArray::from_raw(ids) };
        let query_array = unsafe { JFloatArray::from_raw(query) };

        let vectors = read_float_array(&mut env, &vectors_array)?;
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let query = read_float_array(&mut env, &query_array)?;

        let dim = dim as usize;
        if vectors.len() % dim != 0 {
            return Err("vector buffer length is not a multiple of dim".to_string());
        }
        if query.len() != dim {
            return Err("query dimension does not match index dimension".to_string());
        }
        let n = vectors.len() / dim;
        if ids_i64.len() != n {
            return Err("ids length does not match vector count".to_string());
        }
        let ids_u64: Vec<u64> = ids_i64.into_iter().map(|id| id as u64).collect();

        let mut index = IdMapIndex::new(dim, bit_width as usize)
            .map_err(|e| format!("Could not create turbovec index: {e:?}"))?;
        index
            .add_with_ids(&vectors, &ids_u64)
            .map_err(|e| format!("Could not add vectors to turbovec index: {e:?}"))?;
        index.prepare();

        let search_start = Instant::now();
        let (scores, result_ids) = index.search(&query, k as usize);
        let elapsed_ms = search_start.elapsed().as_millis().min(i64::MAX as u128) as i64;

        let result_count = scores.len().min(result_ids.len());
        let mut out = Vec::<i64>::with_capacity(2 + result_count * 2);
        out.push(elapsed_ms);
        out.push(result_count as i64);
        for i in 0..result_count {
            out.push(result_ids[i] as i64);
            out.push(scores[i].to_bits() as i64);
        }
        Ok(out)
    }));

    let out = match result {
        Ok(Ok(out)) => out,
        Ok(Err(message)) => return throw(&mut env, message),
        Err(_) => return throw(&mut env, "Native turbovec search panicked"),
    };

    match env.new_long_array(out.len() as i32) {
        Ok(array) => {
            if let Err(e) = env.set_long_array_region(&array, 0, &out) {
                return throw(&mut env, format!("Could not write turbovec result: {e}"));
            }
            array.into_raw()
        }
        Err(e) => throw(&mut env, format!("Could not allocate turbovec result: {e}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeInt8Vec_createNative(
    mut env: JNIEnv,
    _class: JClass,
    vectors: jfloatArray,
    ids: jlongArray,
    dim: jint,
) -> jlong {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if dim <= 0 {
            return Err("dim must be positive".to_string());
        }
        let vectors_array = unsafe { JFloatArray::from_raw(vectors) };
        let ids_array = unsafe { JLongArray::from_raw(ids) };

        let vectors = read_float_array(&mut env, &vectors_array)?;
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let dim = dim as usize;
        if vectors.len() % dim != 0 {
            return Err("vector buffer length is not a multiple of dim".to_string());
        }
        let n = vectors.len() / dim;
        if ids_i64.len() != n {
            return Err("ids length does not match vector count".to_string());
        }

        let mut codes = Vec::<i8>::with_capacity(vectors.len());
        let mut scales = Vec::<f32>::with_capacity(n);
        for row in 0..n {
            let start = row * dim;
            let end = start + dim;
            let slice = &vectors[start..end];
            let (row_codes, scale) = quantize_symmetric_i8(slice);
            scales.push(scale);
            codes.extend_from_slice(&row_codes);
        }

        let index = Int8Index {
            dim,
            ids: ids_i64.into_iter().map(|id| id as u64).collect(),
            codes,
            scales,
        };
        Ok(Box::into_raw(Box::new(index)) as jlong)
    }));

    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => throw_long(&mut env, message),
        Err(_) => throw_long(&mut env, "Native int8 index creation panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeInt8Vec_searchNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: jfloatArray,
    k: jint,
) -> jlongArray {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native int8 index handle is null".to_string());
        }
        if k <= 0 {
            return Err("k must be positive".to_string());
        }
        let query_array = unsafe { JFloatArray::from_raw(query) };
        let query = read_float_array(&mut env, &query_array)?;
        let index = unsafe { &*(handle as *const Int8Index) };
        if query.len() != index.dim {
            return Err("query dimension does not match index dimension".to_string());
        }

        let search_start = Instant::now();
        let (query_codes, query_scale) = quantize_symmetric_i8(&query);
        let limit = (k as usize).min(index.ids.len()).max(1);
        let mut scores = Vec::<f32>::with_capacity(limit);
        let mut ids = Vec::<u64>::with_capacity(limit);
        let mut min_idx = 0usize;
        let mut min_score = f32::INFINITY;

        for row in 0..index.ids.len() {
            let start = row * index.dim;
            let int_dot = dot_i8_codes(&index.codes[start..start + index.dim], &query_codes);
            let score = (int_dot as f32) * index.scales[row] * query_scale;

            if scores.len() < limit {
                scores.push(score);
                ids.push(index.ids[row]);
                if scores.len() == limit {
                    min_idx = 0;
                    min_score = scores[0];
                    for i in 1..limit {
                        if scores[i] < min_score {
                            min_score = scores[i];
                            min_idx = i;
                        }
                    }
                }
            } else if score > min_score {
                scores[min_idx] = score;
                ids[min_idx] = index.ids[row];
                min_idx = 0;
                min_score = scores[0];
                for i in 1..limit {
                    if scores[i] < min_score {
                        min_score = scores[i];
                        min_idx = i;
                    }
                }
            }
        }

        let elapsed_ms = search_start.elapsed().as_millis().min(i64::MAX as u128) as i64;
        let mut order: Vec<usize> = (0..scores.len()).collect();
        order.sort_by(|&a, &b| scores[b].partial_cmp(&scores[a]).unwrap_or(std::cmp::Ordering::Equal));

        let mut out = Vec::<i64>::with_capacity(2 + order.len() * 2);
        out.push(elapsed_ms);
        out.push(order.len() as i64);
        for idx in order {
            out.push(ids[idx] as i64);
            out.push(scores[idx].to_bits() as i64);
        }
        Ok(out)
    }));

    let out = match result {
        Ok(Ok(out)) => out,
        Ok(Err(message)) => return throw(&mut env, message),
        Err(_) => return throw(&mut env, "Native int8 search panicked"),
    };

    match env.new_long_array(out.len() as i32) {
        Ok(array) => {
            if let Err(e) = env.set_long_array_region(&array, 0, &out) {
                return throw(&mut env, format!("Could not write int8 search result: {e}"));
            }
            array.into_raw()
        }
        Err(e) => throw(&mut env, format!("Could not allocate int8 search result: {e}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeInt8Vec_closeNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Int8Index));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_createNative(
    mut env: JNIEnv,
    _class: JClass,
    vectors: jfloatArray,
    ids: jlongArray,
    dim: jint,
    bits: jint,
) -> jlong {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if dim <= 0 {
            return Err("dim must be positive".to_string());
        }
        if ![6, 8, 12].contains(&bits) {
            return Err("faithful high-bit TurboQuant scorer currently supports 6, 8, and 12 bits".to_string());
        }
        let vectors_array = unsafe { JFloatArray::from_raw(vectors) };
        let ids_array = unsafe { JLongArray::from_raw(ids) };
        let vectors = read_float_array(&mut env, &vectors_array)?;
        let ids_i64 = read_long_array(&mut env, &ids_array)?;
        let dim = dim as usize;
        let bits = bits as usize;
        let index = build_highbit_index(&vectors, &ids_i64, dim, bits)?;
        Ok(Box::into_raw(Box::new(index)) as jlong)
    }));

    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => throw_long(&mut env, message),
        Err(_) => throw_long(&mut env, "Native high-bit index creation panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_searchNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    query: jfloatArray,
    k: jint,
    allowlist: jlongArray,
) -> jlongArray {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native high-bit index handle is null".to_string());
        }
        if k <= 0 {
            return Err("k must be positive".to_string());
        }
        let query_array = unsafe { JFloatArray::from_raw(query) };
        let query = read_float_array(&mut env, &query_array)?;
        let index = unsafe { &*(handle as *const HighBitIndex) };
        if query.len() != index.dim {
            return Err("query dimension does not match index dimension".to_string());
        }
        let allowlist_set = read_optional_allowlist(&mut env, allowlist)?;
        highbit_search_impl(index, &query, k as usize, allowlist_set.as_ref())
    }));

    let out = match result {
        Ok(Ok(out)) => out,
        Ok(Err(message)) => return throw(&mut env, message),
        Err(_) => return throw(&mut env, "Native high-bit search panicked"),
    };

    match env.new_long_array(out.len() as i32) {
        Ok(array) => {
            if let Err(e) = env.set_long_array_region(&array, 0, &out) {
                return throw(&mut env, format!("Could not write high-bit search result: {e}"));
            }
            array.into_raw()
        }
        Err(e) => throw(&mut env, format!("Could not allocate high-bit search result: {e}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_writeNative(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: jstring,
) -> jboolean {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        if handle == 0 {
            return Err("native high-bit index handle is null".to_string());
        }
        let path = read_jstring(&mut env, path)?;
        let index = unsafe { &*(handle as *const HighBitIndex) };
        write_highbit_index(index, Path::new(&path))
    }));
    match result {
        Ok(Ok(())) => JNI_TRUE,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            JNI_FALSE
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Native high-bit write panicked");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_loadNative(
    mut env: JNIEnv,
    _class: JClass,
    path: jstring,
) -> jlong {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
        let path = read_jstring(&mut env, path)?;
        let index = load_highbit_index(Path::new(&path))?;
        Ok::<jlong, String>(Box::into_raw(Box::new(index)) as jlong)
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => throw_long(&mut env, message),
        Err(_) => throw_long(&mut env, "Native high-bit load panicked"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_vectorCountNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let index = unsafe { &*(handle as *const HighBitIndex) };
    index.ids.len().min(i32::MAX as usize) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_halbertb_clipfinder_domain_compression_NativeHighBitVec_closeNative(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut HighBitIndex));
        }
    }
}
