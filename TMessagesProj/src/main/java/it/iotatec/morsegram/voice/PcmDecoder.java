/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.voice;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodifica un vocale (OGG/Opus) o un videomessaggio rotondo (MP4/AAC) in PCM 16 bit mono a 16 kHz su file.
 * <p>
 * Il decoder nativo citato nel piano (openOpusFile/readOpusFile) non esiste in questo albero: {@code MediaController}
 * espone solo startRecord/writeFrame/stopRecord/cropOpusFile/joinOpusFiles/isOpusFile/getWaveform (opusfile è usata
 * solo in C). Si usa quindi {@link MediaExtractor} + {@link MediaCodec} in modalità sincrona: zero modifiche a jni/.
 * <p>
 * Ricampionamento: il decoder Opus di Android emette sempre 48 kHz; {@code SpeechRecognizer} legge il descrittore con
 * EXTRA_AUDIO_SOURCE_SAMPLING_RATE (default e valore canonico 16000). Downmix a mono (media dei canali), FIR passa-basso
 * a finestra di Hamming (63 tap, taglio 0,4·16 kHz = 6,4 kHz) e interpolazione lineare sul segnale filtrato: per 48→16 kHz
 * il passo è esattamente 3 e l'interpolazione degenera in una decimazione pulita.
 * <p>
 * Oltre ai byte scrive l'RMS di ogni frame da 100 ms ({@link Result#frameRms}): {@link LocalTranscriber} ci sceglie i
 * punti di taglio (silenzi) dei segmenti da ≤ 50 s.
 */
final class PcmDecoder {

    static final int SAMPLE_RATE = 16000;
    static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
    static final int FRAME_MS = 100;
    static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;
    static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    static final class Result {
        long bytes;
        float[] frameRms;
    }

    private PcmDecoder() {
    }

    static Result decode(File src, File dst, AtomicBoolean cancelled) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        BufferedOutputStream out = null;
        final PcmSink sink = new PcmSink();
        try {
            extractor.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            String mime = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    track = i;
                    format = f;
                    mime = m;
                    break;
                }
            }
            if (track < 0) {
                throw new IOException("no audio track in " + src.getName());
            }
            extractor.selectTrack(track);
            long durationMs = 0;
            try {
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000;
                }
            } catch (Throwable ignore) {
            }
            int inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            out = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16);
            sink.out = out;

            Resampler resampler = new Resampler(inRate, SAMPLE_RATE);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long deadline = SystemClock.elapsedRealtime() + 30_000 + durationMs * 2;
            boolean inputDone = false;
            boolean outputDone = false;
            float[] mono = new float[4096];
            while (!outputDone) {
                if (cancelled.get()) {
                    throw new IOException("cancelled");
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw new IOException("decode timeout");
                }
                if (!inputDone) {
                    final int idx = codec.dequeueInputBuffer(10_000);
                    if (idx >= 0) {
                        final ByteBuffer buf = codec.getInputBuffer(idx);
                        final int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat of = codec.getOutputFormat();
                    final int r = of.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : inRate;
                    channels = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (r != inRate) {
                        inRate = r;
                        resampler = new Resampler(inRate, SAMPLE_RATE);
                    }
                } else if (outIdx >= 0) {
                    final ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0 && channels > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        final int frames;
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            final FloatBuffer fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            frames = fb.remaining() / channels;
                            if (mono.length < frames) {
                                mono = new float[frames];
                            }
                            for (int i = 0; i < frames; i++) {
                                float acc = 0f;
                                for (int c = 0; c < channels; c++) {
                                    acc += fb.get();
                                }
                                mono[i] = acc / channels;
                            }
                        } else {
                            final ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                            frames = sb.remaining() / channels;
                            if (mono.length < frames) {
                                mono = new float[frames];
                            }
                            for (int i = 0; i < frames; i++) {
                                float acc = 0f;
                                for (int c = 0; c < channels; c++) {
                                    acc += sb.get();
                                }
                                mono[i] = acc / (channels * 32768f);
                            }
                        }
                        resampler.process(mono, frames, sink);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            sink.flush();
            out.flush();
            final Result res = new Result();
            res.bytes = sink.bytes;
            res.frameRms = sink.frames();
            return res;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignore) {
                }
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                try {
                    codec.release();
                } catch (Throwable ignore) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /** Scrive campioni float [-1,1] come PCM16 little-endian e accumula l'RMS per frame da 100 ms. */
    static final class PcmSink {
        BufferedOutputStream out;
        long bytes;
        private final byte[] buf = new byte[8192];
        private int pos;
        private double frameAcc;
        private int frameCount;
        private float[] rms = new float[1024];
        private int frames;

        void put(float v) throws IOException {
            if (v > 1f) {
                v = 1f;
            } else if (v < -1f) {
                v = -1f;
            }
            final int s = (int) (v * 32767f);
            buf[pos++] = (byte) s;
            buf[pos++] = (byte) (s >> 8);
            bytes += 2;
            if (pos == buf.length) {
                out.write(buf, 0, pos);
                pos = 0;
            }
            frameAcc += v * v;
            if (++frameCount == FRAME_SAMPLES) {
                pushFrame();
            }
        }

        private void pushFrame() {
            if (frames == rms.length) {
                rms = Arrays.copyOf(rms, rms.length * 2);
            }
            rms[frames++] = (float) Math.sqrt(frameAcc / Math.max(1, frameCount));
            frameAcc = 0;
            frameCount = 0;
        }

        void flush() throws IOException {
            if (pos > 0) {
                out.write(buf, 0, pos);
                pos = 0;
            }
            if (frameCount > 0) {
                pushFrame();
            }
        }

        float[] frames() {
            return Arrays.copyOf(rms, frames);
        }
    }

    /** FIR passa-basso + interpolazione lineare, con stato tra una chiamata e l'altra. */
    static final class Resampler {
        private final int inRate;
        private final int outRate;
        private final float[] coef;
        private final float[] carry;
        private final double step;
        private double phase;
        private float prevY;
        private float[] work = new float[0];
        private float[] filtered = new float[0];

        Resampler(int inRate, int outRate) {
            this.inRate = inRate;
            this.outRate = outRate;
            this.step = (double) inRate / outRate;
            if (inRate == outRate) {
                coef = null;
                carry = null;
                return;
            }
            final int taps = 63;
            coef = new float[taps];
            final double fc = 0.4 * Math.min(inRate, outRate) / inRate;
            final int m = taps - 1;
            double sum = 0;
            for (int i = 0; i < taps; i++) {
                final double x = i - m / 2.0;
                final double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
                final double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / m);
                coef[i] = (float) (sinc * w);
                sum += coef[i];
            }
            for (int i = 0; i < taps; i++) {
                coef[i] /= (float) sum;
            }
            carry = new float[taps - 1];
        }

        void process(float[] x, int n, PcmSink sink) throws IOException {
            if (n <= 0) {
                return;
            }
            if (coef == null) {
                for (int i = 0; i < n; i++) {
                    sink.put(x[i]);
                }
                return;
            }
            final int taps = coef.length;
            final int total = n + taps - 1;
            if (work.length < total) {
                work = new float[total];
            }
            if (filtered.length < n) {
                filtered = new float[n];
            }
            System.arraycopy(carry, 0, work, 0, taps - 1);
            System.arraycopy(x, 0, work, taps - 1, n);
            for (int j = 0; j < n; j++) {
                float acc = 0f;
                for (int k = 0; k < taps; k++) {
                    acc += coef[k] * work[j + k];
                }
                filtered[j] = acc;
            }
            int j = (int) Math.floor(phase);
            while (j + 1 < n) {
                final float y0 = j < 0 ? prevY : filtered[j];
                final float y1 = filtered[j + 1];
                final float frac = (float) (phase - j);
                sink.put(y0 + frac * (y1 - y0));
                phase += step;
                j = (int) Math.floor(phase);
            }
            prevY = filtered[n - 1];
            phase -= n;
            System.arraycopy(work, total - (taps - 1), carry, 0, taps - 1);
        }
    }
}
