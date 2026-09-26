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

    private static final String MENU_CONNECT = "Connect";
    private static final String MENU_DISCONNECT = "Disconnect";
    private static final String MENU_BLOCK = "Block";
    private static final String MENU_UNBLOCK = "UnBlock";
    private static final String MENU_DELETE = "Delete";

    private FragmentViewModel viewModel;
    public EcclesAdapter chatAdapter;
    public EcclesAdapter callAdapter;
    public EcclesAdapter videoAdapter;
    public EcclesAdapter currentAdapter;
    private RecyclerView listView;

    /** Which of {@link #TAB_CHATS}/{@link #TAB_CALLS}/{@link #TAB_VIDEO} this instance shows. */
    public int index;

    /** Defaults to a safe non-null message so {@link #setFabIcon} can never NPE even if this
     *  fragment is somehow shown before {@link #index} has been resolved from its arguments. */
    private String emptyText = EMPTY_TEXT_LOAD_FAILED;

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
                        break;
                    case TAB_CALLS:
                        viewModel.liveData.setValue(callAdapter);
                        emptyText = EMPTY_TEXT_CALLS;
                        break;
                    case TAB_VIDEO:
                        viewModel.liveData.setValue(videoAdapter);
                        emptyText = EMPTY_TEXT_VIDEO;
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
        if (!loadData()) {
            View empty = inflater.inflate(R.layout.empty_view, container, false);
            TextView emptyTextView = empty.findViewById(R.id.empty_text);
            emptyTextView.setText(emptyText);
            setFabIcon(empty);
            return empty;
        }

        View view = inflater.inflate(R.layout.fragment_main, container, false);
        listView = view.findViewById(R.id.fragment_list);
        listView.setLayoutManager(new LinearLayoutManager(getContext()));
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
        if (index == TAB_CALLS) {
            ((MajorActivity) getActivity()).clearStatus(false);
        } else if (index == TAB_VIDEO) {
            ((MajorActivity) getActivity()).clearStatus(true);
        }
        super.onResume();
    }

    @Override
    public void onChanged(EcclesAdapter adapter) {
        if (listView != null) {
            listView.setAdapter(adapter);
            currentAdapter = adapter;
        }
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

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        EcclesApplication app = (EcclesApplication) getActivity().getApplication();
        BluetoothDevice device = app.adapter.getRemoteDevice((String) view.getTag());

        try {
            menu.add(app.isConnected(device.getAddress()) ? MENU_DISCONNECT : MENU_CONNECT)
                    .setContentDescription((String) view.getTag());
        } catch (Exception e) {
            Log.e("MainFragment", "failed to add connect/disconnect menu entry", e);
        }
        menu.add(MENU_DELETE).setContentDescription((String) view.getTag());
        menu.add(app.currentActivity.block(device.getAddress(), EcclesActivity.BLOCK_CHECK_BLOCK) ? MENU_UNBLOCK : MENU_BLOCK)
                .setContentDescription((String) view.getTag());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
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
                        Toast.makeText(app, "unable to delete file located at\n" + path, Toast.LENGTH_LONG).show();
                    }
                }
            }
            app.chatStore.clear(address);
            app.chatBase.delete(address);
            ClassicCompat.clearUnreads(getActivity(), address);
        } else if (index == TAB_CALLS) {
            app.callBase.delete(address);
        } else if (index == TAB_VIDEO) {
            app.videoBase.delete(address);
        }

        adapter.pojos.remove(pojo);
        adapter.notifyDataSetChanged();
    }
}
