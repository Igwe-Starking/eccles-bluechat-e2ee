package starking.eccles.Surface;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.core.app.ActivityCompat;

import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.MajorActivity;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.activities.CallActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.activities.VideoActivity;

/**
 * Row data for the contacts / active-calls / device-selection lists.
 * <p>
 * This is a plain data model: it holds no {@link android.view.View} of its own. Rendering is
 * done by {@link EcclesAdapter.ItemViewHolder} / {@link EcclesAdapter.HeaderViewHolder}, which
 * bind a recycled row View to whichever {@code EcclesPojo} is currently at that adapter
 * position. Earlier versions of this class inflated and permanently owned a {@code View} per
 * instance, which meant {@link EcclesAdapter} (then a {@code ListView} {@code BaseAdapter})
 * never actually recycled rows - every visible item held its own fully-inflated view hierarchy
 * for as long as it existed. Splitting data from presentation is what makes real
 * {@code RecyclerView} row recycling possible.
 */
public class EcclesPojo {

    public static final String AVAIL = "Available devices";
    public static final String PAIRED = "Paired Devices";
    public static final String ACTIVE = "Active Devices";

    public static final int CHAT_CLIENT = 12;
    public static final int CALL_CLIENT = 14;
    public static final int VIDEO_CLIENT = 16;

    public Context con;
    public String Title;
    public String Message;
    public Drawable image;
    public String status;
    public String tag;
    public int rssi;
    public BluetoothDevice device;

    /** True for the "Active Devices" / "Available devices" / "Paired Devices" section-header
     *  rows used by {@link SelectActivity}'s device list; false for ordinary device/chat rows. */
    public boolean isHeader;
    public String headerLabel;
    public int headerLabelColor;
    public String headerSubtitle;
    public String headerNoText;
    /** -1 means "use the layout's default color"; see {@link EcclesAdapter.HeaderViewHolder#bind}. */
    public int headerNoTextColor = -1;
    public boolean headerNoTextVisible = true;

    /** True for the single "tap and hold a device for options" hint row inserted by
     *  {@link EcclesAdapter#setSearchHint}. Scrolls with the rest of the list and stays visible
     *  through filtering, mirroring how {@code ListView.addHeaderView} behaved before this list
     *  moved to {@code RecyclerView} (a header view added that way is, under the hood, just an
     *  extra row the adapter always reports). */
    public boolean isSearchHint;
    public CharSequence searchHintText;

    /** Set by {@link EcclesAdapter} when this pojo is added to its list, so {@link #checkActive}
     *  and {@link #checkUnread} can request a rebind of just this row. */
    EcclesAdapter adapter;

    public EcclesPojo(BluetoothDevice device, Context con, int layout, Drawable icon, boolean attachAddress, String status, int rssi) {
        this.con = con;
        this.device = device;
        this.image = icon;
        this.status = status;
        this.rssi = rssi;
        if (device != null) {
            this.Title = device.getName();
            this.tag = device.getAddress();
            if (attachAddress) {
                this.Message = device.getAddress();
            }
        }
    }

    /** Section-header row constructor, used only by {@link EcclesAdapter} for the sublisted
     *  (device-selection) list. */
    public EcclesPojo(Context con, int res, String header, String sub) {
        this.con = con;
        this.isHeader = true;
        this.tag = header;
        this.headerLabel = header;
        this.headerSubtitle = sub;

        switch (header) {
            case AVAIL:
                headerLabelColor = Color.BLUE;
                headerNoText = ((EcclesActivity) con).requestPermission(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION},
                        EcclesActivity.LOCATION_PERM)
                        ? "No Available device found" : "permission not granted";
                break;
            case ACTIVE:
                headerLabelColor = Color.GREEN;
                headerNoText = "No Active Device Found";
                break;
            case PAIRED:
                headerLabelColor = Color.RED;
                headerNoText = "No Paired Device found";
                break;
            default:
                break;
        }

        if (!((EcclesApplication) con.getApplicationContext()).adapter.isEnabled()) {
            headerNoText = "bluetooth is off";
        }
    }

    /** Search-hint row constructor; see {@link #isSearchHint}. Package-private: only
     *  {@link EcclesAdapter#setSearchHint} creates one. */
    EcclesPojo(CharSequence searchHintText) {
        this.isSearchHint = true;
        this.searchHintText = searchHintText;
    }

    /** Package-private: only {@link EcclesAdapter.ItemViewHolder}'s click handling calls this. */
    void connect(String add) {
        EcclesActivity activity = (EcclesActivity) con;
        BluetoothAdapter bluetoothAdapter = ((EcclesApplication) activity.getApplication()).adapter;

        if (((EcclesApplication) activity.getApplication()).isConnected(add)) {
            return;
        }
        BluetoothDevice target = bluetoothAdapter.getRemoteDevice(add);
        int ind = 0;
        if (activity instanceof SelectActivity) {
            ind = ((SelectActivity) activity).index;
        } else if (activity instanceof MajorActivity) {
            ind = ((MajorActivity) activity).fragment.index;
        }
        if (ind == 3) {
            if (activity.checkPro(() -> activity.connect(target, true))) {
                activity.connect(target, true);
            }
            return;
        }
        activity.connect(target, true);
    }

    public void launch(int ind) {
        switch (ind) {
            case 1: {
                Intent intent = new Intent(con, ChatActivity.class);
                intent.putExtra("device", device);
                ActivityCompat.startActivity(con, intent, null);
                return;
            }
            case 2: {
                Intent intent = new Intent(con, CallActivity.class);
                intent.putExtra("device", device);
                intent.putExtra("type", CallActivity.TYPE_REQUEST);
                ActivityCompat.startActivity(con, intent, null);
                return;
            }
            case 3: {
                Runnable startVideoCall = () -> {
                    Intent intent = new Intent(con, VideoActivity.class);
                    intent.putExtra("device", device);
                    intent.putExtra("type", CallActivity.TYPE_REQUEST);
                    ActivityCompat.startActivity(con, intent, null);
                };
                if (((EcclesActivity) con).checkPro(startVideoCall)) {
                    startVideoCall.run();
                }
                return;
            }
            default:
        }
    }

    /** Requests that this row be re-rendered with current live state (e.g. connection status).
     *  A no-op if this pojo isn't currently attached to an adapter, or if its row is currently
     *  scrolled off-screen (RecyclerView correctly skips rebinding rows that aren't visible). */
    public void checkActive() {
        if (adapter != null) {
            adapter.notifyPojoChanged(this);
        }
    }

    public void checkUnread() {
        if (adapter != null) {
            adapter.notifyPojoChanged(this);
        }
    }
}
