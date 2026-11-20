package net.polze.adolarradio.local.sync;

import net.polze.adolarradio.BuildConfig;
import net.polze.adolarradio.local.LastFmScrobble;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small HTTPS client for a self-hosted Adolar Scrobbler instance's
 * last.fm-2.0-compatible Web Service API. Mirrors {@link LastFmApi} closely
 * (same signing/request shape - Adolar Scrobbler deliberately speaks the
 * same protocol) but the API root is admin-configured per build rather than
 * a fixed constant, since it points at whichever instance the admin runs.
 */
public final class AdolarScrobblerApi {
    private static final String USER_AGENT = "AdolarNext-Android/" + BuildConfig.VERSION_NAME;

    private AdolarScrobblerApi() { }

    public static boolean configured() {
        return !BuildConfig.ADOLAR_SCROBBLER_API_ROOT.isEmpty()
                && !BuildConfig.ADOLAR_SCROBBLER_API_KEY.isEmpty()
                && !BuildConfig.ADOLAR_SCROBBLER_SHARED_SECRET.isEmpty();
    }

    public static Session login(String username, String password) throws Exception {
        Map<String,String> p = new LinkedHashMap<>();
        p.put("api_key", BuildConfig.ADOLAR_SCROBBLER_API_KEY);
        p.put("method", "auth.getMobileSession");
        p.put("password", password);
        p.put("username", username);
        JSONObject json = postSigned(p);
        JSONObject session = json.getJSONObject("session");
        return new Session(session.getString("name"), session.getString("key"));
    }

    public static List<Boolean> scrobble(List<LastFmScrobble> entries, String sessionKey) throws Exception {
        Map<String,String> p = new LinkedHashMap<>();
        p.put("api_key", BuildConfig.ADOLAR_SCROBBLER_API_KEY);
        p.put("method", "track.scrobble");
        p.put("sk", sessionKey);
        for (int i=0; i<entries.size(); i++) {
            LastFmScrobble e = entries.get(i);
            p.put("artist["+i+"]", e.artist);
            p.put("track["+i+"]", e.title);
            p.put("timestamp["+i+"]", String.valueOf(e.startedAtUtc / 1000L));
            if (!e.album.isEmpty()) p.put("album["+i+"]", e.album);
            if (e.durationSeconds > 0) p.put("duration["+i+"]", String.valueOf(e.durationSeconds));
            p.put("chosenByUser["+i+"]", "1");
        }
        JSONObject response = postSigned(p);
        JSONObject root = response.getJSONObject("scrobbles");
        Object raw=root.opt("scrobble");
        List<Boolean> accepted=new ArrayList<>();
        if(raw instanceof JSONArray) {
            JSONArray rows=(JSONArray)raw;
            for(int i=0;i<rows.length();i++) accepted.add(isAccepted(rows.getJSONObject(i)));
        } else if(raw instanceof JSONObject) {
            accepted.add(isAccepted((JSONObject)raw));
        }
        while(accepted.size()<entries.size()) accepted.add(false);
        return accepted;
    }

    private static boolean isAccepted(JSONObject row) {
        JSONObject ignored=row.optJSONObject("ignoredMessage");
        return ignored == null || ignored.optInt("code",0)==0;
    }

    private static JSONObject postSigned(Map<String,String> params) throws Exception {
        if (!configured()) throw new IllegalStateException("Adolar Scrobbler not configured");
        params.put("api_sig", signature(params));
        params.put("format", "json");
        return request("POST", params);
    }

    private static JSONObject request(String method, Map<String,String> params) throws Exception {
        String encoded = encode(params);
        String root = BuildConfig.ADOLAR_SCROBBLER_API_ROOT;
        URL url = new URL(root + ("GET".equals(method) ? "?" + encoded : ""));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                try (OutputStream out=connection.getOutputStream()) {
                    out.write(encoded.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code=connection.getResponseCode();
            InputStream stream=code>=200 && code<300 ? connection.getInputStream() : connection.getErrorStream();
            String body=read(stream);
            JSONObject json=new JSONObject(body);
            if (code<200 || code>=300 || json.has("error"))
                throw new IllegalStateException("Adolar Scrobbler " + json.optInt("error", code) + ": " + json.optString("message", body));
            return json;
        } finally { connection.disconnect(); }
    }

    private static String signature(Map<String,String> params) throws Exception {
        List<String> keys=new ArrayList<>(params.keySet()); Collections.sort(keys);
        StringBuilder value=new StringBuilder();
        for(String key:keys) if(!"format".equals(key) && !"callback".equals(key)) value.append(key).append(params.get(key));
        value.append(BuildConfig.ADOLAR_SCROBBLER_SHARED_SECRET);
        byte[] digest=MessageDigest.getInstance("MD5").digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(); for(byte b:digest) hex.append(String.format("%02x",b&0xff));
        return hex.toString();
    }

    private static String encode(Map<String,String> params) throws Exception {
        StringBuilder body=new StringBuilder();
        for(Map.Entry<String,String> e:params.entrySet()) {
            if(body.length()>0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));
        }
        return body.toString();
    }

    private static String read(InputStream in) throws Exception {
        if(in==null) return ""; try(InputStream input=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; while((n=input.read(b))!=-1) out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }

    public static final class Session {
        public final String username, key;
        Session(String username,String key){this.username=username;this.key=key;}
    }
}
