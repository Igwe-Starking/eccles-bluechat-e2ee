package starking.eccles.Surface;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.SectionIndexer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.ui.MainFragment;
import starking.eccles.util.EcclesIcon;
import starking.eccles.util.FriendlyDate;

/**
 * {@link RecyclerView.Adapter} backing the Chats/Calls/Video tabs ({@link
 * MainFragment}) and the device-selection list ({@link
 * SelectActivity}). Each row's {@code View} is created once per {@link RecyclerView.ViewHolder}
 * and reused ("recycled") as the user scrolls; {@link #onBindViewHolder} re-populates whichever
 * {@link EcclesPojo} is now at that position into the already-inflated, cached view references
 * held by the holder, instead of inflating a new view hierarchy per row.
 */
public class EcclesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements Filterable, SectionIndexer {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;
    private static final int VIEW_TYPE_SEARCH_HINT = 2;

    public ArrayList<EcclesPojo> pojos;
    private final Context con;
    private final boolean sublisted;
    public EcclesPojo ac, av, pa;
    volatile ArrayList<EcclesPojo> copy;

    /**
     * {@code RecyclerView.Adapter} has no Fragment/Activity of its own to call
     * {@code registerForContextMenu(View)} on, so the adapter's owner supplies this hook once
     * (see {@code MainFragment#onCreate}, {@code SelectActivity#onStart}) and the adapter calls
     * it exactly once per newly-created row {@code View} in {@link #onCreateViewHolder}. This is
     * actually more correct than the previous per-data-add registration: a given {@code View}
     * object only needs registering once, no matter how many different rows of data it goes on
     * to display as it's recycled.
     */
    public interface ContextMenuRegistrar {
        void register(View view);
    }

    private ContextMenuRegistrar contextMenuRegistrar;

    public void setContextMenuRegistrar(ContextMenuRegistrar registrar) {
        this.contextMenuRegistrar = registrar;
    }

    public EcclesAdapter(Context con, boolean sublist) {
        this.con = con;
        this.sublisted = sublist;
        pojos = new ArrayList<>();

        if (sublist) {
            ac = new EcclesPojo(con, R.layout.avail_head, EcclesPojo.ACTIVE, "Devices you are currently connected to");
            av = new EcclesPojo(con, R.layout.avail_head, EcclesPojo.AVAIL, "Devices that are currently reachable");
            pa = new EcclesPojo(con, R.layout.avail_head, EcclesPojo.PAIRED, "Paired Devices");

            pojos.add(ac);
            pojos.add(av);
            pojos.add(pa);
        }

        copy = pojos;
    }

    @Override
    public int getItemCount() {
        return pojos.size();
    }

    @Override
    public int getItemViewType(int position) {
        EcclesPojo p = pojos.get(position);
        if (p.isSearchHint) return VIEW_TYPE_SEARCH_HINT;
        return p.isHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.avail_head, parent, false);
            return new HeaderViewHolder(v);
        }
        if (viewType == VIEW_TYPE_SEARCH_HINT) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.header_view, parent, false);
            v.setBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.main));
            return new SearchHintViewHolder(v);
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.pojo_view, parent, false);
        if (contextMenuRegistrar != null) {
            contextMenuRegistrar.register(v);
        }
        return new ItemViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        EcclesPojo pojo = pojos.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind(pojo);
        } else if (holder instanceof SearchHintViewHolder) {
            ((SearchHintViewHolder) holder).bind(pojo);
        } else {
            ((ItemViewHolder) holder).bind(pojo);
        }
    }

    /** Inserts (once) the "tap and hold a device for options" hint as the first row. Replaces
     *  the old {@code ListView.addHeaderView(createTextHeader(...))} flow; see
     *  {@link EcclesPojo#isSearchHint}. */
    public void setSearchHint(CharSequence text) {
        if (!pojos.isEmpty() && pojos.get(0).isSearchHint) {
            return;
        }
        pojos.add(0, new EcclesPojo(text));
        notifyDataSetChanged();
    }

    /** Rebinds a single row in place, without a full-list refresh. */
    void notifyPojoChanged(EcclesPojo pojo) {
        int idx = pojos.indexOf(pojo);
        if (idx >= 0) {
            notifyItemChanged(idx);
        }
    }

    // --- Header mutation helpers ---
    //
    // These replace what used to be direct '.wrapper.findViewById(...)' access from
    // SelectActivity / AvailReceiver. That's no longer possible now that row Views are
    // recycled rather than permanently owned per-item, so header state lives on the EcclesPojo
    // itself and these helpers update it plus request a rebind.

    public void setHeaderSubtitle(EcclesPojo header, String subtitle) {
        header.headerSubtitle = subtitle;
        notifyPojoChanged(header);
    }

    public void setHeaderNoText(EcclesPojo header, String text) {
        header.headerNoText = text;
        notifyPojoChanged(header);
    }

    public void setHeaderNoTextColor(EcclesPojo header, int color) {
        header.headerNoTextColor = color;
        notifyPojoChanged(header);
    }

    public void setHeaderNoTextVisible(EcclesPojo header, boolean visible) {
        header.headerNoTextVisible = visible;
        notifyPojoChanged(header);
    }

    public Filter getFilter() {
        return new Filter() {
            @Override
            public synchronized FilterResults performFiltering(CharSequence cs) {
                FilterResults res = new FilterResults();

                if (cs == null || cs.length() <= 0) {
                    res.count = copy.size();
                    res.values = copy;
                } else {
                    ArrayList<EcclesPojo> out = new ArrayList<>();
                    if (sublisted) {
                        if (!copy.isEmpty() && copy.get(0).isSearchHint) {
                            out.add(copy.get(0));
                        }
                        out.add(ac);
                        out.add(av);
                        out.add(pa);
                    }
                    for (EcclesPojo p : copy) {
                        if (p.Title != null && p.Title.toUpperCase().startsWith(cs.toString().toUpperCase())) {
                            addFiltered(out, p);
                        }
                    }
                    for (EcclesPojo p : copy) {
                        if (p.Title != null && p.Title.toUpperCase().contains(cs.toString().toUpperCase()) && !out.contains(p)) {
                            addFiltered(out, p);
                        }
                    }

                    res.count = out.size();
                    res.values = out;
                }
                return res;
            }

            @Override
            public synchronized void publishResults(CharSequence cs, FilterResults res1) {
                //noinspection unchecked
                pojos = (ArrayList<EcclesPojo>) res1.values;
                notifyDataSetChanged();
            }
        };
    }

    private void addFiltered(ArrayList<EcclesPojo> out, EcclesPojo p) {
        if (sublisted && p.status != null) {
            switch (p.status) {
                case "Active":
                    out.add(out.indexOf(ac) + 1, p);
                    break;
                case "Paired":
                    out.add(out.indexOf(pa) + 1, p);
                    break;
                case "Available":
                    out.add(out.indexOf(av) + 1, p);
                    break;
                default:
            }
        } else {
            out.add(p);
        }
    }

    public void add(ArrayList<EcclesPojo> al, EcclesPojo p) {
        p.adapter = this;

        if (sublisted && p.status != null) {
            try {
                switch (p.status) {
                    case "Active":
                        al.add(al.indexOf(ac) + 1, p);
                        setHeaderNoTextVisible(ac, false);
                        break;
                    case "Available":
                        al.add(al.indexOf(av) + 1, p);
                        setHeaderNoTextVisible(av, false);
                        break;
                    case "Paired":
                        al.add(al.indexOf(pa) + 1, p);
                        setHeaderNoTextVisible(pa, false);
                        setHeaderSubtitle(pa, (pojos.size() - pojos.indexOf(pa) - 1) + " Paired devices Found");
                        break;
                    default:
                }
                notifyDataSetChanged();
                return;
            } catch (Exception e) {
                Log.e("Eccles", "failed to add pojo", e);
                return;
            }
        }
        pojos.add(p);
        notifyDataSetChanged();
    }

    public int getPositionForSection(int p) {
        return pojos.indexOf(pojos.get(p));
    }

    public int getSectionForPosition(int i) {
        return pojos.indexOf(pojos.get(i));
    }

    public Object[] getSections() {
        return pojos.toArray();
    }

    public EcclesPojo findPojoWithTag(String tag) {
        for (EcclesPojo p : pojos) {
            if (p.tag != null && p.tag.equals(tag)) {
                return p;
            }
        }
        return null;
    }

    /** Row view holder for ordinary device / chat / call / video-call rows ({@code pojo_view.xml}). */
    static class ItemViewHolder extends RecyclerView.ViewHolder {
        final ImageView profileIcon;
        final ImageView activeStatus;
        final ImageView unreadStatus;
        final ImageView signal;
        final TextView title;
        final TextView message;
        final TextView statusText;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            profileIcon = itemView.findViewById(R.id.profile_icon);
            activeStatus = itemView.findViewById(R.id.active_status);
            unreadStatus = itemView.findViewById(R.id.unread_status);
            signal = itemView.findViewById(R.id.signal);
            title = itemView.findViewById(R.id.title);
            message = itemView.findViewById(R.id.message);
            statusText = itemView.findViewById(R.id.status);
        }

        void bind(EcclesPojo pojo) {
            // Every mutable visual property is reset to a known baseline before applying
            // pojo-specific overrides below. This is essential now that itemView is recycled:
            // without it, a row could visibly (and confusingly) carry over e.g. a red "blocked"
            // status color left behind by whatever different pojo last occupied this same View.
            itemView.setTag(pojo.tag);
            profileIcon.setImageDrawable(pojo.image);
            profileIcon.setOnClickListener(v -> new ProfileDialog(pojo).post());
            title.setText(pojo.Title);
            message.setText(pojo.Message);
            message.setTextColor(Color.parseColor("#22bb22"));
            statusText.setVisibility(View.VISIBLE);
            statusText.setTextColor(Color.parseColor("#5555ee"));
            signal.setVisibility(View.INVISIBLE);
            activeStatus.setImageDrawable(null);
            unreadStatus.setImageDrawable(null);

            if (pojo.rssi == -1) {
                statusText.setText("Unsupported device");
                statusText.setTextColor(Color.RED);
            } else if (pojo.rssi > 0 && pojo.rssi <= 4) {
                signal.setVisibility(View.VISIBLE);
                statusText.setText(ClassicCompat.checkOnline(pojo.device) ? "Online" : "Offline");
                statusText.setTextColor(Color.BLUE);
                signal.setImageLevel(pojo.rssi);
            } else if (pojo.status != null) {
                String[] parts = pojo.status.split("\b");
                switch (pojo.rssi) {
                    case EcclesPojo.CHAT_CLIENT:
                        signal.setImageDrawable(EcclesIcon.resize(30, 30, R.drawable.ic_chat, pojo.con));
                        signal.setVisibility(View.VISIBLE);
                        message.setText(parts[0]);
                        message.setTextColor(Color.BLACK);
                        statusText.setText(FriendlyDate.simplify(parts[1]));
                        bindActive(pojo);
                        bindUnread(pojo);
                        break;
                    case EcclesPojo.CALL_CLIENT:
                        signal.setImageDrawable(EcclesIcon.resize(30, 30, R.drawable.ic_call, pojo.con));
                        signal.setVisibility(View.VISIBLE);
                        message.setText(FriendlyDate.simplify(parts[1]));
                        statusText.setVisibility(View.GONE);
                        bindActive(pojo);
                        bindMissed(pojo, parts[0]);
                        break;
                    case EcclesPojo.VIDEO_CLIENT:
                        signal.setImageDrawable(EcclesIcon.resize(30, 30, R.drawable.ic_video_call, pojo.con));
                        signal.setVisibility(View.VISIBLE);
                        message.setText(FriendlyDate.simplify(parts[1]));
                        statusText.setVisibility(View.GONE);
                        bindActive(pojo);
                        bindMissed(pojo, parts[0]);
                        break;
                    default:
                }
            }

            if (pojo.con instanceof EcclesActivity && pojo.device != null
                    && ((EcclesActivity) pojo.con).block(pojo.device.getAddress(), EcclesActivity.BLOCK_CHECK_BLOCK)) {
                statusText.setText("blocked");
                statusText.setTextColor(Color.RED);
            }

            itemView.setOnClickListener(v -> onItemClick(pojo));
        }

        private void bindActive(EcclesPojo pojo) {
            EcclesApplication app = (EcclesApplication) pojo.con.getApplicationContext();
            int icon = app.isConnected(pojo.tag) ? android.R.drawable.presence_online : android.R.drawable.presence_invisible;
            activeStatus.setImageResource(icon);
        }

        private void bindUnread(EcclesPojo pojo) {
            int unread = ClassicCompat.queryDeviceUnreads(pojo.con, pojo.tag);
            if (unread > 0) {
                unreadStatus.setImageDrawable(ClassicCompat.createUnreadBit(pojo.con, String.valueOf(unread), Color.TRANSPARENT, Color.GREEN));
            }
        }

        private void bindMissed(EcclesPojo pojo, String missedCountText) {
            unreadStatus.setImageDrawable(ClassicCompat.createCallBit(pojo.con, Integer.parseInt(missedCountText)));
        }

        private void onItemClick(EcclesPojo pojo) {
            EcclesApplication app = (EcclesApplication) ((EcclesActivity) pojo.con).getApplication();
            if ("Active".equals(pojo.status) || app.isConnected(pojo.Message)) {
                if (pojo.con instanceof SelectActivity) {
                    pojo.launch(((SelectActivity) pojo.con).index);
                }
            }

            switch (pojo.rssi) {
                case EcclesPojo.CHAT_CLIENT:
                    pojo.launch(1);
                    return;
                case EcclesPojo.CALL_CLIENT:
                    pojo.launch(2);
                    return;
                case EcclesPojo.VIDEO_CLIENT:
                    pojo.launch(3);
                    return;
                default:
            }

            if (pojo.rssi == -1) {
                ((EcclesActivity) pojo.con).showDialog(
                        "Bluechat does not recognize this device as an android device\nYou can still try to connect to this device if you insist",
                        "Connect", "Cancel", null, () -> pojo.connect(pojo.Message), null);
                return;
            }

            pojo.connect(pojo.Message);
        }
    }

    /** Row view holder for the single search-hint row inserted by {@link #setSearchHint};
     *  the styled text/marquee state was previously set on a throwaway View by the old
     *  {@code createTextHeader}, and is now bound here each time the row is (re)created. */
    static class SearchHintViewHolder extends RecyclerView.ViewHolder {
        SearchHintViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        void bind(EcclesPojo pojo) {
            ((TextView) itemView).setText(pojo.searchHintText);
            itemView.setSelected(true);
        }
    }

    /** Row view holder for the "Active Devices" / "Available devices" / "Paired Devices"
     *  section-header rows ({@code avail_head.xml}), used only in the sublisted device list. */
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private static final int DEFAULT_NO_TEXT_COLOR = Color.parseColor("#ff2222");

        final TextView head;
        final TextView availText;
        final TextView noText;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            head = itemView.findViewById(R.id.head);
            availText = itemView.findViewById(R.id.avail_text);
            noText = itemView.findViewById(R.id.notext);
        }

        void bind(EcclesPojo pojo) {
            head.setText(pojo.headerLabel);
            head.setTextColor(pojo.headerLabelColor);
            availText.setText(pojo.headerSubtitle);
            noText.setText(pojo.headerNoText);
            noText.setTextColor(pojo.headerNoTextColor != -1 ? pojo.headerNoTextColor : DEFAULT_NO_TEXT_COLOR);
            noText.setVisibility(pojo.headerNoTextVisible ? View.VISIBLE : View.GONE);
        }
    }
}
