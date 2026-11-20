package net.polze.adolarradio.local;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Minimal ID3v2 POPM reader/writer used for Mp3tag-compatible play counters. */
public final class Id3PlayCount {
    public static final String OWNER = "adolar@polze.net";
    private static final int MAX_TAG_BYTES = 32 * 1024 * 1024;

    private Id3PlayCount() { }

    public static int read(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Header header = readHeader(input);
            if (header == null || header.size > MAX_TAG_BYTES) return 0;
            byte[] tag = readExactly(input, header.size);
            int best = 0;
            int offset = header.extendedOffset(tag);
            while (offset + 10 <= tag.length) {
                String id = new String(tag, offset, 4, StandardCharsets.ISO_8859_1);
                if (id.charAt(0) == 0) break;
                int size = header.version == 4 ? syncSafe(tag, offset + 4) : int32(tag, offset + 4);
                if (size < 0 || offset + 10L + size > tag.length) break;
                if ("POPM".equals(id)) best = Math.max(best, popmCount(tag, offset + 10, size));
                offset += 10 + size;
            }
            return best;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Rewrites only Adolar's POPM owner and preserves all other frames and audio bytes. */
    public static boolean write(Context context, Uri uri, int count) {
        if (count < 0) return false;
        ContentResolver resolver = context.getContentResolver();
        File copy = null;
        try {
            copy = File.createTempFile("adolar-playcount-", ".mp3", context.getCacheDir());
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
                if (in == null) return false;
                pipe(in, out);
            }
            Header old;
            byte[] tag;
            try (InputStream in = new FileInputStream(copy)) {
                old = readHeader(in);
                if (old == null) {
                    old = new Header(3, 0, 0);
                    tag = new byte[0];
                } else {
                    if (old.version < 3 || old.version > 4 || old.size > MAX_TAG_BYTES
                            || (old.flags & 0x80) != 0) return false;
                    tag = readExactly(in, old.size);
                }
            }
            byte[] frames = replaceOwnPopm(old, tag, count);
            try (OutputStream out = resolver.openOutputStream(uri, "rwt");
                 InputStream audio = new FileInputStream(copy)) {
                if (out == null) return false;
                long audioOffset = old.originalSize == 0 ? 0 : 10L + old.originalSize
                        + ((old.version == 4 && (old.flags & 0x10) != 0) ? 10L : 0L);
                skipExactly(audio, audioOffset);
                out.write(new byte[]{'I','D','3',(byte) old.version,0,(byte)(old.flags & 0x40)});
                out.write(toSyncSafe(frames.length));
                out.write(frames);
                pipe(audio, out);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (copy != null) //noinspection ResultOfMethodCallIgnored
                copy.delete();
        }
    }

    private static byte[] replaceOwnPopm(Header header, byte[] tag, int count) throws Exception {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        int offset = header.extendedOffset(tag);
        if (offset > 0) frames.write(tag, 0, offset);
        while (offset + 10 <= tag.length) {
            String id = new String(tag, offset, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break;
            int size = header.version == 4 ? syncSafe(tag, offset + 4) : int32(tag, offset + 4);
            if (size < 0 || offset + 10L + size > tag.length) break;
            boolean own = "POPM".equals(id) && owner(tag, offset + 10, size).equals(OWNER);
            if (!own) frames.write(tag, offset, 10 + size);
            offset += 10 + size;
        }
        byte[] owner = OWNER.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(owner); body.write(0); body.write(0); // neutral rating
        byte[] counter = unsignedCounter(count);
        body.write(counter);
        byte[] payload = body.toByteArray();
        frames.write("POPM".getBytes(StandardCharsets.ISO_8859_1));
        frames.write(header.version == 4 ? toSyncSafe(payload.length) : toInt32(payload.length));
        frames.write(new byte[]{0,0});
        frames.write(payload);
        return frames.toByteArray();
    }

    private static int popmCount(byte[] data, int start, int size) {
        int end = start + size, zero = start;
        while (zero < end && data[zero] != 0) zero++;
        int counter = zero + 2;
        long value = 0;
        while (counter < end) value = Math.min(Integer.MAX_VALUE, (value << 8) | (data[counter++] & 0xff));
        return (int) value;
    }

    private static String owner(byte[] data, int start, int size) {
        int end = start + size, zero = start;
        while (zero < end && data[zero] != 0) zero++;
        return new String(data, start, zero - start, StandardCharsets.ISO_8859_1);
    }

    private static Header readHeader(InputStream input) throws Exception {
        byte[] head = readExactly(input, 10);
        if (head.length < 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') return null;
        int size = syncSafe(head, 6);
        return new Header(head[3] & 0xff, head[5] & 0xff, size);
    }

    private static final class Header {
        final int version, flags, size, originalSize;
        Header(int version, int flags, int size) { this.version=version; this.flags=flags; this.size=size; this.originalSize=size; }
        int extendedOffset(byte[] tag) {
            if ((flags & 0x40) == 0 || tag.length < 4) return 0;
            int length = version == 4 ? syncSafe(tag, 0) : int32(tag, 0) + 4;
            return Math.min(Math.max(length, 0), tag.length);
        }
    }

    private static byte[] unsignedCounter(int count) {
        if (count <= 0xff) return new byte[]{(byte) count};
        if (count <= 0xffff) return new byte[]{(byte)(count >> 8),(byte)count};
        if (count <= 0xffffff) return new byte[]{(byte)(count >> 16),(byte)(count >> 8),(byte)count};
        return toInt32(count);
    }
    private static int syncSafe(byte[] b, int o) { return ((b[o]&127)<<21)|((b[o+1]&127)<<14)|((b[o+2]&127)<<7)|(b[o+3]&127); }
    private static int int32(byte[] b, int o) { return ((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255); }
    private static byte[] toSyncSafe(int n) { return new byte[]{(byte)((n>>21)&127),(byte)((n>>14)&127),(byte)((n>>7)&127),(byte)(n&127)}; }
    private static byte[] toInt32(int n) { return new byte[]{(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n}; }
    private static byte[] readExactly(InputStream in, int size) throws Exception {
        byte[] data = new byte[size]; int at=0, read;
        while (at<size && (read=in.read(data,at,size-at))!=-1) at+=read;
        return at==size ? data : Arrays.copyOf(data,at);
    }
    private static void skipExactly(InputStream in, long count) throws Exception {
        while (count>0) { long n=in.skip(count); if(n<=0){ if(in.read()==-1) throw new IllegalStateException("short file"); n=1; } count-=n; }
    }
    private static void pipe(InputStream in, OutputStream out) throws Exception { byte[] b=new byte[64*1024]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); }
}
