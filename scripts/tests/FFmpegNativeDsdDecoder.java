package com.sony.walkmanpro;

import java.io.*;
import java.lang.reflect.Field;

public class FFmpegNativeDsdDecoder {
    static { System.load(System.getProperty("walkman.native")); }
    static native int nativeDecodeDsdFile(int fd, Object callback);
    static native String nativeGetFFmpegVersion();

    public static class Callback {
        int wanted = -2, first = -1, channels, calls, rate;
        long samples, duration, last;
        boolean stopEarly, stopOnProbe, seekDuringWrite, drained;
        public boolean isCancelled() { return stopOnProbe || (stopEarly && calls >= 4); }
        public int onNativeFormat(int decoderRate, int sourceChannels, long durationMs) {
            if (decoderRate <= 0 || sourceChannels < 1) throw new AssertionError("format");
            channels = Math.min(2, sourceChannels);
            duration = durationMs;
            return rate = decoderRate > 400000 ? 176400 : 88200;
        }
        public int getPlaybackCommand() { int requested = wanted; wanted = -2; return requested; }
        public int onNativePcmChunk(float[] pcm, int size, long ms) {
            for (int i=0; i<size; i++) if (!Float.isFinite(pcm[i])) throw new AssertionError("sample");
            if (ms < last && !seekDuringWrite) throw new AssertionError("position regressed");
            if (first == -1) first = (int)ms;
            last = ms;
            samples += size;
            calls++;
            if (seekDuringWrite && calls == 3) { wanted = 0; return 1; }
            if (seekDuringWrite && calls == 4 && ms != 0) throw new AssertionError("seek to zero " + ms);
            return 0;
        }
        public int onNativeEnd() { drained = true; return 0; }
    }

    static void run(String file, int seek, boolean cancel, boolean seekDuringWrite, boolean invalid) throws Exception {
        Callback cb = new Callback();
        cb.wanted = seek;
        cb.stopEarly = cancel && !invalid;
        cb.stopOnProbe = cancel && invalid;
        cb.seekDuringWrite = seekDuringWrite;
        try (FileInputStream input = new FileInputStream(file)) {
            Field field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            int status = nativeDecodeDsdFile(field.getInt(input.getFD()), cb);
            System.out.printf("%s seek=%d cancel=%s seekWhileWriting=%s status=%d first=%d frames=%d duration=%d%n",
                file, seek, cancel, seekDuringWrite, status, cb.first, cb.channels == 0 ? 0 : cb.samples/cb.channels, cb.duration);
            if (cancel || invalid) {
                if (status >= 0 || cb.drained) throw new AssertionError("expected failure/cancel");
            } else {
                if (status != 0 || !cb.drained || cb.calls == 0) throw new AssertionError("expected successful decode");
                if (seek >= 0 && Math.abs(cb.first - seek) > 1) throw new AssertionError("unexpected seek position: " + cb.first);
                if (seek == -2 && !seekDuringWrite && cb.duration > 0
                    && Math.abs(cb.samples/cb.channels - cb.rate*cb.duration/1000) > cb.rate/100)
                    throw new AssertionError("unexpected output frame count");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println(nativeGetFFmpegVersion());
        for (String file : new String[] {"64.dsf", "128.dsf", "256.dsf", "raw.dff", "dst.dff"}) {
            run("fixtures/" + file, -2, false, false, false);
            run("fixtures/" + file, file.equals("dst.dff") ? 60 : 500, false, false, false);
        }
        run("fixtures/64.dsf", -2, true, false, false);
        run("fixtures/64.dsf", -2, true, false, true);
        run("fixtures/64.dsf", -2, false, true, false);
        run("fixtures/malformed.dsf", -2, false, false, true);
    }
}
