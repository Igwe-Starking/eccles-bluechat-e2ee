package starking.eccles.bluechat.ui;

import android.app.ActivityOptions;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;

import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.MajorActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;

/**
 * One tab of the main "Chats / Calls / Video" pager. Which list this instance shows is fixed at
 * creation time via {@link #newInstance}'s {@code position} argument and stored in {@link #index}
 * ({@link #TAB_CHATS}, {@link #TAB_CALLS}, or {@link #TAB_VIDEO}).
 */
public class MainFragment extends Fragment implements Observer<EcclesAdapter> {

    private static final String ARG_POSITION = "position";

    public static final int TAB_CHATS = 1;
    public static final int TAB_CALLS = 2;
    public static final int TAB_VIDEO = 3;

    private static final String EMPTY_TEXT_CHATS = "No Chats";
    private static final String EMPTY_TEXT_CALLS = "No Calls";
    private static final String EMPTY_TEXT_VIDEO = "No Video Chats";
    private static final String EMPTY_TEXT_LOAD_FAILED = "Failed to load chats";

    // tipText (the small persistent hint above the list) used to be hardcoded in
    // fragment_main.xml to always say "chat", even on the Calls/Video tabs - correct per tab now.
    private static final String TIP_TEXT_CHATS = "tap and hold a chat for options";
    private static final String TIP_TEXT_CALLS = "tap and hold a call for options";
    private static final String TIP_TEXT_VIDEO = "tap and hold a video call for options";

    private static final String MENU_CONNECT = "Connect";
    private static final String MENU_DISCONNECT = "Disconnect";
    private static final String MENU_BLOCK = "Block";
    private static final String MENU_UNBLOCK = "Unblock";
    private static final String MENU_DELETE = "Delete";

    private FragmentViewModel viewModel;
    public EcclesAdapter chatAdapter;
    public EcclesAdapter callAdapter;
    public EcclesAdapter videoAdapter;
    public EcclesAdapter currentAdapter;
    private RecyclerView listView;
    private TextView emptyTextView;
    private TextView tipTextView;

    /** Which of {@link #TAB_CHATS}/{@link #TAB_CALLS}/{@link #TAB_VIDEO} this instance shows. */
    public int index;

    /** Defaults to a safe non-null message so {@link #setFabIcon} can never NPE even if this
     *  fragment is somehow shown before {@link #index} has been resolved from its arguments. */
    private String emptyText = EMPTY_TEXT_LOAD_FAILED;
    private String tipText = TIP_TEXT_CHATS;

    public static MainFragment newInstance(int position) {
        MainFragment fragment = new MainFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_POSITION, position);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(FragmentViewModel.class);

        if (chatAdapter == null) chatAdapter = new EcclesAdapter(getActivity(), false);
        if (callAdapter == null) callAdapter = new EcclesAdapter(getActivity(), false);
        if (videoAdapter == null) videoAdapter = new EcclesAdapter(getActivity(), false);
        // RecyclerView.Adapter has no Fragment of its own to call registerForContextMenu(View)
        // on, so each adapter is given this Fragment's registerForContextMenu as a callback,
        // invoked once per newly-created row View (see EcclesAdapter#onCreateViewHolder).
        chatAdapter.setContextMenuRegistrar(this::registerForContextMenu);
        callAdapter.setContextMenuRegistrar(this::registerForContextMenu);
        videoAdapter.setContextMenuRegistrar(this::registerForContextMenu);

        if (getActivity() != null && viewModel != null) {
            ((MajorActivity) getActivity()).fragment = this;
            if (getArguments() != null) {
                index = getArguments().getInt(ARG_POSITION);
                switch (index) {
                    case TAB_CHATS:
                        viewModel.liveData.setValue(chatAdapter);
                        emptyText = EMPTY_TEXT_CHATS;
                        tipText = TIP_TEXT_CHATS;
                        break;
                    case TAB_CALLS:
                        viewModel.liveData.setValue(callAdapter);
                        emptyText = EMPTY_TEXT_CALLS;
                        tipText = TIP_TEXT_CALLS;
                        break;
                    case TAB_VIDEO:
                        viewModel.liveData.setValue(videoAdapter);
                        emptyText = EMPTY_TEXT_VIDEO;
                        tipText = TIP_TEXT_VIDEO;
                        break;
                    default:
                        // index 0 (or an unrecognized value) has no associated list; emptyText
                        // keeps its safe default.
                        break;
                }
            }
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Always the same layout now - see fragment_main.xml's empty_text comment for why a
        // separate empty_view layout, swapped in only here at creation time, used to cause the
        // "no chats until you swipe away and back" bug.
        View view = inflater.inflate(R.layout.fragment_main, container, false);
        listView = view.findViewById(R.id.fragment_list);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
        emptyTextView = view.findViewById(R.id.empty_text);
        emptyTextView.setText(emptyText);
        tipTextView = view.findViewById(R.id.tipText);
        tipTextView.setText(tipText);
        loadData();
        viewModel.data.observe(getViewLifecycleOwner(), this);
        setFabIcon(view);

        if (getActivity() instanceof MajorActivity) {
            MajorActivity majorActivity = (MajorActivity) getActivity();
            majorActivity.fragment = this;
            majorActivity.actionSearch.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextChange(String query) {
                    EcclesAdapter adapter = getCurrentAdapter();
                    if (adapter == null) return false;
                    adapter.getFilter().filter(query);
                    return true;
                }

                @Override
                public boolean onQueryTextSubmit(String query) {
                    return false;
                }
            });
        }
        return view;
    }

    @Override
    public void onResume() {
        // Badge clearing now lives in MajorActivity (tab-selected listener + checkStatus()).
        // It used to run here, but ViewPager also resumes the neighbouring page off-screen, so
        // this cleared a tab's badge without it being visited.
        refresh();
        // Periodic refresh while this tab is visible, on top of the usual onResume() refresh
        // and the explicit updates remove()/etc. already trigger - e.g. an incoming message
        // bumping a conversation to the top, or another device's call/video history arriving,
        // show up within 10s without needing to leave and come back to this tab.
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        super.onResume();
    }

    @Override
    public void onChanged(EcclesAdapter adapter) {
        if (listView != null) {
            listView.setAdapter(adapter);
            currentAdapter = adapter;
            updateEmptyState();
        }
    }

    /** Toggles fragment_list/tipText/empty_text's visibility to match whether the current tab's
     *  adapter actually has anything in it - called instead of swapping in a whole different
     *  layout, so a later refresh() can update what's on screen without needing to recreate the
     *  fragment. tipText ("tap and hold a chat for options") is a hint about items in the list,
     *  so it's tied to the same empty/non-empty state as the list itself - showing a hint about
     *  tapping and holding a chat right next to "No Chats" doesn't make sense. */
    private void updateEmptyState() {
        if (emptyTextView == null || listView == null || tipTextView == null) return;
        EcclesAdapter adapter = getCurrentAdapter();
        boolean empty = adapter == null || adapter.pojos.isEmpty();
        emptyTextView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        tipTextView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void setFabIcon(View root) {
        FloatingActionButton fab = root.findViewById(R.id.start_button);
        fab.setTag(emptyText);

        if (EMPTY_TEXT_CHATS.equals(emptyText)) {
            fab.setImageResource(R.drawable.ic_chat);
        } else if (EMPTY_TEXT_VIDEO.equals(emptyText)) {
            fab.setImageResource(R.drawable.ic_video_call);
        } else if (EMPTY_TEXT_CALLS.equals(emptyText)) {
            fab.setImageResource(R.drawable.ic_call);
        }

        fab.setOnClickListener(view -> {
            Intent intent = new Intent(getActivity(), SelectActivity.class);
            intent.putExtra("index", index);
            ActivityCompat.startActivity(getActivity(), intent,
                    ActivityOptions.makeScaleUpAnimation(fab, (int) fab.getX(), (int) fab.getY(), 60, 60).toBundle());
        });
    }

    private boolean loadData() {
        try {
            EcclesActivity activity = (EcclesActivity) getActivity();
            switch (index) {
                case TAB_CHATS:
                    return loadChats(activity);
                case TAB_CALLS:
                    return loadCalls(activity);
                case TAB_VIDEO:
                    return loadVideoCalls(activity);
                default:
                    return false;
            }
        } catch (Exception e) {
            Log.e("Eccles", "loadData() failed", e);
            emptyText = EMPTY_TEXT_LOAD_FAILED;
            return false;
        }
    }

    private boolean loadChats(EcclesActivity activity) {
        index = TAB_CHATS;
        return loadInto(chatAdapter, activity.chatBase.read(EcclesPojo.CHAT_CLIENT));
    }

    private boolean loadCalls(EcclesActivity activity) {
        index = TAB_CALLS;
        return loadInto(callAdapter, activity.callBase.read(EcclesPojo.CALL_CLIENT));
    }

    private boolean loadVideoCalls(EcclesActivity activity) {
        index = TAB_VIDEO;
        return loadInto(videoAdapter, activity.videoBase.read(EcclesPojo.VIDEO_CLIENT));
    }

    private boolean loadInto(EcclesAdapter adapter, EcclesPojo[] pojos) {
        if (pojos == null || pojos.length == 0) {
            return false;
        }
        for (EcclesPojo pojo : pojos) {
            adapter.add(adapter.pojos, pojo);
        }
        return true;
    }

    public void onConnectionChanged(BluetoothDevice device, boolean connected) {
        EcclesAdapter adapter = getCurrentAdapter();
        if (adapter != null && adapter.pojos.size() > 0) {
            EcclesPojo pojo = adapter.findPojoWithTag(device.getAddress());
            if (pojo != null) {
                pojo.checkActive();
            }
        }
    }

    public void checkUnread(String address) {
        EcclesAdapter adapter = getCurrentAdapter();
        if (adapter != null && adapter.pojos.size() > 0) {
            EcclesPojo pojo = adapter.findPojoWithTag(address);
            if (pojo != null) {
                pojo.checkUnread();
            }
        }
    }

    public EcclesAdapter getCurrentAdapter() {
        switch (index) {
            case TAB_CHATS:
                return chatAdapter;
            case TAB_CALLS:
                return callAdapter;
            case TAB_VIDEO:
                return videoAdapter;
            default:
                return null;
        }
    }

    /** The Fragment whose own row opened the current context menu. Every Fragment alive in the
     *  ViewPager is offered the selected item in turn until one returns true, and each used to
     *  return true unconditionally - so a neighbouring tab (e.g. Calls, when the press was on a
     *  Video row; confirmed by logging) handled Delete against its own list, found nothing and
     *  quit. Only the owner handles it now; the others return false so it moves on. */
    private static MainFragment contextMenuOwner;

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        contextMenuOwner = this;
        EcclesApplication app = (EcclesApplication) getActivity().getApplication();
        BluetoothDevice device = app.adapter.getRemoteDevice((String) view.getTag());

        try {
            menu.add(app.isConnected(device.getAddress()) ? MENU_DISCONNECT : MENU_CONNECT)
                    .setContentDescription((String) view.getTag());
        } catch (Exception e) {
            Log.e("MainFragment", "failed to add connect/disconnect menu entry", e);
        }
        menu.add("Rename").setContentDescription((String) view.getTag());
        menu.add(MENU_DELETE).setContentDescription((String) view.getTag());
        menu.add(app.currentActivity.block(device.getAddress(), EcclesActivity.BLOCK_CHECK_BLOCK) ? MENU_UNBLOCK : MENU_BLOCK)
                .setContentDescription((String) view.getTag());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (contextMenuOwner != this) return false;
        EcclesActivity activity = (EcclesActivity) getActivity();
        BluetoothDevice device = activity.app.adapter.getRemoteDevice(item.getContentDescription().toString());
        String selection = item.getTitle().toString();

        if (MENU_CONNECT.equals(selection)) {
            activity.connect(device, true);
        } else if (MENU_DISCONNECT.equals(selection)) {
            activity.disconnect(device, true);
        } else if (MENU_UNBLOCK.equals(selection)) {
            activity.block(device.getAddress(), EcclesActivity.BLOCK_UNBLOCK);
        } else if (MENU_BLOCK.equals(selection)) {
            activity.block(device.getAddress(), EcclesActivity.BLOCK_BLOCK);
        } else if ("Rename".equals(selection)) {
            activity.renamePeer(device);
        } else if (MENU_DELETE.equals(selection)) {
            remove(device.getAddress(), activity.app);
        }
        return true;
    }

    public void remove(String address, EcclesApplication app) {
        EcclesAdapter adapter = getCurrentAdapter();
        EcclesPojo pojo = adapter == null ? null : adapter.findPojoWithTag(address);
        if (pojo == null) {
            return;
        }

        if (index == TAB_CHATS) {
            String[] attachments = app.chatStore.getFiles(address);
            if (attachments != null) {
                for (String path : attachments) {
                    if (!new File(path).delete()) {
                        Toast.makeText(app, "Couldn't remove the saved file from your phone", Toast.LENGTH_LONG).show();
                    }
                }
            }
            boolean messagesCleared = app.chatStore.clear(address);
            boolean contactCleared = app.chatBase.delete(address);
            if (!messagesCleared && !contactCleared) {
                // Both report "nothing matched" - most likely this was already deleted (e.g. a
                // second tap before the list redrew), but logging it means a genuine address
                // mismatch wouldn't go unnoticed and silently look like it worked.
                Log.w("MainFragment", "delete: no chatStore/chatBase rows matched address " + address);
            }
            ClassicCompat.clearUnreads(getActivity(), address);
            // A ChatActivity for this exact device can still be alive right now - paused in the
            // back stack, or visible side-by-side with this list in split-screen/multi-window
            // (both activities declare resizeableActivity/allowEmbedded) - with the pre-delete
            // history already sitting in its adapter's in-memory list. The two lines above only
            // touched persisted storage, which a live instance never re-reads on its own; this
            // is what actually makes that instance - not just some future fresh one - go blank
            // too, which is the "it's gone from the list but still loads in chat" symptom.
            if (getActivity() != null) {
                EcclesApplication ea = (EcclesApplication) getActivity().getApplication();
                if (ea.currentActivity instanceof ChatActivity) {
                    ChatActivity chat = (ChatActivity) ea.currentActivity;
                    if (chat.device != null && chat.device.getAddress().equals(address) && chat.adapter != null) {
                        chat.adapter.pojos.clear();
                        chat.adapter.notifyDataSetChanged();
                    }
                }
            }
        } else if (index == TAB_CALLS) {
            app.callBase.delete(address);
        } else if (index == TAB_VIDEO) {
            app.videoBase.delete(address);
        }

        adapter.pojos.remove(pojo);
        adapter.notifyDataSetChanged();
    }

    public void refresh() {
        EcclesAdapter adapter = getCurrentAdapter();
        if (adapter == null) return;
        adapter.pojos.clear();
        loadData();
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private static final long REFRESH_INTERVAL_MS = 10_000;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    public void onPause() {
        // Only ticks while this tab is actually visible/resumed - no point refreshing a list
        // nobody's looking at, and it'd needlessly compete with the two tabs that are.
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }
}
