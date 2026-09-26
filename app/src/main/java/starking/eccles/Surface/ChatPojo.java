package starking.eccles.Surface;

import android.net.Uri;

import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.activities.ChatActivity;

/**
 * Row data for a single chat message. Like {@link EcclesPojo}, this is a plain data model with
 * no {@link android.view.View} of its own - rendering and all view-holder-specific state (media
 * playback, icon caching) live in {@link ChatAdapter}'s view holders, which are created once
 * and reused ("recycled") as the user scrolls, instead of one permanently-inflated view per
 * message as before.
 */
public class ChatPojo {

    public EcclesMessage messenger;
    public Uri uri;
    public ChatActivity act;
    public boolean self;
    public String tag;
    public boolean isSelected;
    public int index;

    /** "sending" / "sent" / "failed", or null once acknowledged/for historical messages with no
     *  pending delivery state. Set via {@link #setStatus} and rendered by the bound view holder. */
    public String status;

    /** Set by {@link ChatAdapter} when this pojo is added to its list, so {@link #setStatus} can
     *  request a rebind of just this row. */
    ChatAdapter adapter;

    public ChatPojo(EcclesMessage m, ChatActivity act) {
        this.messenger = m;
        this.act = act;
        this.tag = m.sender;
        this.self = m.sender == null || m.sender.equals("");
    }

    /** Requests that this row be re-rendered with its current status/selection state. A no-op
     *  if this pojo isn't currently attached to an adapter, or if its row is scrolled off-screen
     *  (in which case it will simply render correctly next time it's bound). */
    public ChatPojo setStatus(String s) {
        this.status = s;
        if (adapter != null) {
            adapter.notifyPojoChanged(this);
        }
        return this;
    }

    public ChatPojo setIndex(int ind) {
        this.index = ind;
        return this;
    }

    /** Requests a rebind to reflect the new selection-highlight state; see {@link
     *  ChatActivity#select}. */
    public ChatPojo setSelected(boolean selected) {
        this.isSelected = selected;
        if (adapter != null) {
            adapter.notifyPojoChanged(this);
        }
        return this;
    }
}
