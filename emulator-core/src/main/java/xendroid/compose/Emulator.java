// SPDX-License-Identifier: WTFPL
package xendroid.compose;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

public class Emulator extends xendroid.emulator.Emulator{
    public static Emulator get=null;
    public static void load_library(){
        if(get!=null)
            throw new RuntimeException("Emulator already loaded");
        get=new Emulator();
        System.loadLibrary("e");
    }

    /*public void key_event(int keycode,boolean pressed){
        throw new RuntimeException("Not implemented");
        final int unused=-1;
        super.key_event(keycode,pressed,unused);
    }*/

    public native void setup_context(Context ctx);
    public native void setup_launch_args(String[] args);
    public  native void setup_uri_info_list_file(String path);
    public native String simple_device_info();
    public native String generate_config_xml(String config_path);
    // Debug overlay text (FPS / frame time / compile count), or null when the
    // "Display|show_debug_overlay" setting is off. Polled from EmulatorActivity.
    public native String debug_overlay_text();
    // Last presented guest-frame interval, in ms (raw present-to-present delta,
    // NOT averaged). 0 before the first present / right after a pause.
    public native double last_frame_time_ms();
    // Instant fps = 1000/last_frame_time_ms (0 when no frame timed yet). Distinct
    // from the smoothed average fps embedded in debug_overlay_text().
    public native double instant_fps();
    // Effective Display|show_debug_overlay (the live cvar, with any per-game config
    // overlay applied by LoadGameConfig at boot). Poll post-boot: the per-game override
    // lands on the detached boot thread, so this only reflects it after the game loads.
    public native boolean show_debug_overlay_enabled();

    // Mount the ISO at isoPath (a REAL host ISO path == game.launchUri in
    // real-path mode), walk its filesystem and pack it into a VERIFIED .zar at
    // outZarPath (also a real host path, e.g. beside the .iso). Returns X_STATUS
    // (0 == success, non-zero == failure). The native side only reports success
    // after the .zar re-opens and matches the source disc (file-count + total
    // bytes); on any failure the partial .zar is removed and the caller MUST keep
    // the ISO. MUST be called off the main thread (blocking VFS walk + zstd
    // compress + verify): dispatch from a background coroutine (Dispatchers.IO).
    public native int compressIsoToZar(String isoPath,String outZarPath);

    // Fraction 0..1 of the ISO->.zar compression currently running on another
    // thread (0 when none is in flight). Poll it for a determinate progress bar.
    public native float compressProgress();

    // ---- Real-path (All Files Access) scan probes: take an ABSOLUTE host path
    // (no Context: no ContentResolver / fd). The core's real-path DiscImageDevice /
    // DiscZarchiveDevice / Extract*Metadata back these. format: 0=ISO, 1=XEX_FOLDER,
    // 2=ZAR (== TID_FMT_* in C++, == GameFormat.titleIdCode). GameInfo.uri is echoed
    // back as the input abs path.

    // Header-only title-id read for ISO / XEX_FOLDER / ZAR from a real path. path = the ISO
    // file (ISO), default.xex (XEX_FOLDER) or .zar file (ZAR). 8-char uppercase-hex id, or
    // null for unsupported/unreadable/00000000.
    public native String   title_id_from_path(String path,int format) throws RuntimeException;

    // Combined name+icon(+titleId) read for ISO / XEX_FOLDER / ZAR from a real path (one XEX
    // decompress + XDBF/SPA parse). Returns a GameInfo (fields may individually be null), or
    // null when nothing was readable. MUST run off the main thread.
    public native GameInfo meta_from_path(String path,int format)     throws RuntimeException;

    // GOD container header read from a real path: title_name + icon + title id. Returns a
    // GameInfo, or null for a non-GOD/unreadable container. MUST run off the main thread.
    public native GameInfo meta_info_from_god_path(String path)       throws RuntimeException;


    public static class GameInfo{

        public String uri;
        public String name;
        public String titleId;   // 8-char uppercase hex, or null for non-GOD / unreadable
        public int fd;
        public byte[] icon;


        static JSONObject to_json(GameInfo  info) throws JSONException {
            JSONObject json=new JSONObject();

            json.put("uri",info.uri);
            if(info.name!=null)
                json.put("name",info.name);
            if(info.titleId!=null)
                json.put("titleId",info.titleId);

            if(info.icon!=null)
                json.put("icon", Base64.getEncoder().encodeToString(info.icon));
            return json;
        }

        static GameInfo from_json(JSONObject json) throws JSONException {
            GameInfo info=new GameInfo();
            info.uri=json.getString("uri");
            if(json.has("name"))
                info.name=json.getString("name");
            if(json.has("titleId"))
                info.titleId=json.getString("titleId");
            if(json.has("icon"))
                info.icon=Base64.getDecoder().decode(json.getString("icon"));

            return info;
        }
    }
}
