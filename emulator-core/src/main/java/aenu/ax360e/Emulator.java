// SPDX-License-Identifier: WTFPL
package aenu.ax360e;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

public class Emulator extends aenu.emulator.Emulator{
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
    public native void setup_document_file_tree(DocumentFile tree);
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
    public static int nc_open_uri_fd(Context ctx,Uri uri) {
        try {
            ParcelFileDescriptor pfd_ = ctx.getContentResolver().openFileDescriptor(uri, "r");
            int game_fd=pfd_.detachFd();
            pfd_.close();
            return game_fd;
        } catch (Exception e) {
            Log.e("ax360e",e.toString());
            return -1;
        }
    }


    public native GameInfo meta_info_from_god_game(Context ctx,String uri) throws RuntimeException;

    // Boot-free title-id read for ISO (format 0) / XEX_FOLDER (format 1) via a light
    // XEX2 header parse. uri = ISO container uri or default.xex child uri. Returns
    // an 8-char uppercase-hex id, or null for unsupported/unreadable/00000000.
    public native String title_id_from_uri(Context ctx,String uri,int format) throws RuntimeException;

    // Boot-free combined meta read for ISO (format 0) / XEX_FOLDER (format 1): decompresses
    // default.xex ONCE into a transient guest address space and pulls the XDBF/SPA title NAME,
    // title icon PNG, and title id in a single pass. uri = ISO container uri or default.xex
    // child uri. Returns a GameInfo (its name/icon/titleId fields may individually be null when
    // absent/unreadable), or null when nothing was readable. Heavier than title_id_from_uri
    // (full XEX decompress); MUST run off the main thread.
    public native GameInfo meta_from_xex(Context ctx,String uri,int format) throws RuntimeException;


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
