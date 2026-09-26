package starking.eccles.Surface;

import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.recyclerview.widget.RecyclerView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.activities.ViewActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.data.EcclesStorage;
import starking.eccles.util.EcclesIcon;
import starking.eccles.util.FriendlyDate;

/**
 * {@link RecyclerView.Adapter} backing {@link ChatActivity}'s message list. Each of the 8
 * (message subtype x self-or-peer) layout combinations is a distinct RecyclerView item type, so
 * rows are inflated once per {@link MessageViewHolder} and reused as the user scrolls, instead
 * of one permanently-inflated view per message as before.
 * <p>
 * Audio playback state (the {@link MediaPlayer}, its {@link Chronometer}/{@link ProgressBar})
 * belongs to the view holder, not the {@link ChatPojo}: it's inherently tied to one on-screen
 * row, not to the underlying data. {@link #onViewRecycled} stops and releases any player still
 * attached to a holder before it's reused for a different message, so scrolling away from a
 * playing voice note can never leave audio running with no visible connection to it.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    private static final int TYPE_ME_TEXT = 0, TYPE_U_TEXT = 1;
    private static final int TYPE_ME_AUDIO = 2, TYPE_U_AUDIO = 3;
    private static final int TYPE_ME_IMAGE = 4, TYPE_U_IMAGE = 5;
    private static final int TYPE_ME_VIDEO = 6, TYPE_U_VIDEO = 7;

    private final ChatActivity activity;
    public ArrayList<ChatPojo> pojos;

    public ChatAdapter(ChatActivity activity) {
        this.activity = activity;
        pojos = new ArrayList<>();
    }

    @Override
    public int getItemCount() {
        return pojos.size();
    }

    @Override
    public int getItemViewType(int position) {
        ChatPojo p = pojos.get(position);
        int subtypeBase;
        switch (p.messenger.subtype) {
            case EcclesMessage.SUBTYPE_AUDIO:
                subtypeBase = TYPE_ME_AUDIO;
                break;
            case EcclesMessage.SUBTYPE_IMAGE:
                subtypeBase = TYPE_ME_IMAGE;
                break;
            case EcclesMessage.SUBTYPE_VIDEO:
                subtypeBase = TYPE_ME_VIDEO;
                break;
            case EcclesMessage.SUBTYPE_TEXT:
            default:
                subtypeBase = TYPE_ME_TEXT;
                break;
        }
        return p.self ? subtypeBase : subtypeBase + 1;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        switch (viewType) {
            case TYPE_ME_TEXT: layout = R.layout.me_text; break;
            case TYPE_U_TEXT: layout = R.layout.u_text; break;
            case TYPE_ME_AUDIO: layout = R.layout.me_audio; break;
            case TYPE_U_AUDIO: layout = R.layout.u_audio; break;
            case TYPE_ME_IMAGE: layout = R.layout.me_image; break;
            case TYPE_U_IMAGE: layout = R.layout.u_image; break;
            case TYPE_ME_VIDEO: layout = R.layout.me_video; break;
            case TYPE_U_VIDEO: default: layout = R.layout.u_video; break;
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MessageViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        pojos.get(position).adapter = this;
        holder.bind(pojos.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull MessageViewHolder holder) {
        holder.stopAudioIfPlaying();
        super.onViewRecycled(holder);
    }

    /** Rebinds a single row in place, without a full-list refresh. */
    void notifyPojoChanged(ChatPojo pojo) {
        int idx = pojos.indexOf(pojo);
        if (idx >= 0) {
            notifyItemChanged(idx);
        }
    }

    /** Adds a newly sent/received message and correctly wires it up to this adapter (so, e.g.,
     *  a later delivery-receipt call to {@link ChatPojo#setStatus} can request a rebind). */
    public void add(ChatPojo p) {
        p.adapter = this;
        pojos.add(p);
        notifyItemInserted(pojos.size() - 1);
    }

    public ChatPojo findPojoWithTag(String t) {
        for (ChatPojo p : pojos) {
            if (p.tag != null && p.tag.equals(t)) {
                return p;
            }
        }
        return null;
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView statusText;
        private final TextView timeText;
        private final TextView textBody;
        private final ImageView imageBody;
        private final ImageView videoThumb;
        private final ImageView audioPlayButton;
        private final ProgressBar audioProgress;
        private final Chronometer duration;
        private final View videoPlayButton;

        private MediaPlayer player;
        private boolean audioPaused;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.icon);
            statusText = itemView.findViewById(R.id.status);
            timeText = itemView.findViewById(R.id.time);
            textBody = itemView.findViewById(R.id.text);
            imageBody = itemView.findViewById(R.id.image);
            videoThumb = itemView.findViewById(R.id.video);
            audioPlayButton = itemView.findViewById(R.id.audio);
            audioProgress = itemView.findViewById(R.id.audio_pro);
            duration = itemView.findViewById(R.id.duration);
            videoPlayButton = itemView.findViewById(R.id.v_play);
        }

        void bind(ChatPojo pojo) {
            stopAudioIfPlaying();

            // Reset to a known baseline before applying pojo-specific state, since this row's
            // View may previously have displayed a completely different message.
            itemView.setBackgroundColor(pojo.isSelected ? Color.BLUE : Color.TRANSPARENT);
            if (statusText != null) {
                statusText.setVisibility(View.GONE);
            }

            if (icon != null) {
                RoundedBitmapDrawable d = EcclesIcon.resize(40, 40, ClassicCompat.queryDeviceIcon(pojo.messenger.sender, activity), activity);
                icon.setImageDrawable(d != null ? d : EcclesIcon.resize(40, 40, R.drawable.user, activity));
            }
            if (statusText != null && pojo.status != null) {
                statusText.setText(pojo.status);
                statusText.setTextColor(statusColor(pojo.status));
                statusText.setVisibility(View.VISIBLE);
            }
            if (timeText != null) {
                timeText.setText(FriendlyDate.simplify(pojo.messenger.date));
            }

            switch (pojo.messenger.subtype) {
                case EcclesMessage.SUBTYPE_TEXT:
                    bindText(pojo);
                    break;
                case EcclesMessage.SUBTYPE_AUDIO:
                    bindAudio(pojo);
                    break;
                case EcclesMessage.SUBTYPE_IMAGE:
                    bindImage(pojo);
                    break;
                case EcclesMessage.SUBTYPE_VIDEO:
                    bindVideo(pojo);
                    break;
                default:
            }

            itemView.setOnClickListener(v -> {
                if (activity.selecting) {
                    activity.select(pojo);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (!activity.selecting) {
                    activity.select(pojo);
                }
                return true;
            });
        }

        private int statusColor(String status) {
            switch (status) {
                case "sending": return Color.YELLOW;
                case "sent": return Color.GREEN;
                case "failed": return Color.RED;
                default: return Color.TRANSPARENT;
            }
        }

        private void bindText(ChatPojo pojo) {
            textBody.setText(new String(pojo.messenger.data, StandardCharsets.UTF_8));
        }

        private void bindImage(ChatPojo pojo) {
            try {
                pojo.uri = EcclesStorage.resolveUri(activity, new String(pojo.messenger.data));
                if (pojo.uri == null) throw new Exception("failed to decrypt media");
            } catch (Exception e) {
                Log.e("Eccles", "failed to get uri", e);
                Toast.makeText(activity, "Failed to process received Image\nStorage permission might not have been granted", Toast.LENGTH_LONG).show();
                return;
            }
            imageBody.setImageURI(pojo.uri);
            imageBody.setOnClickListener(v -> activity.view(pojo.uri, ViewActivity.VIEW_IMAGE));
        }

        private void bindVideo(ChatPojo pojo) {
            try {
                pojo.uri = EcclesStorage.resolveUri(activity, new String(pojo.messenger.data));
                if (pojo.uri == null) throw new Exception("failed to decrypt media");
            } catch (Exception e) {
                Toast.makeText(activity, "Failed to process received Video\nStorage permission might not have been granted", Toast.LENGTH_LONG).show();
                return;
            }
            videoThumb.setImageBitmap(EcclesStorage.extractThumb(activity, pojo.uri));
            long l = EcclesStorage.extractDuration(activity, pojo.uri);
            duration.setBase(SystemClock.elapsedRealtime() - l);
            videoPlayButton.setOnClickListener(v -> activity.view(pojo.uri, ViewActivity.VIEW_VIDEO));
        }

        private void bindAudio(ChatPojo pojo) {
            try {
                pojo.uri = EcclesStorage.resolveUri(activity, new String(pojo.messenger.data));
                if (pojo.uri == null) throw new Exception("failed to decrypt media");
            } catch (Exception e) {
                Toast.makeText(activity, "Failed to process received Audio\nStorage permission might not have been granted", Toast.LENGTH_LONG).show();
                return;
            }

            long l = EcclesStorage.extractDuration(activity, pojo.uri);
            duration.setBase(SystemClock.elapsedRealtime() - l);
            duration.stop();
            audioProgress.setProgress(0);
            audioProgress.setMax((int) TimeUnit.MILLISECONDS.toSeconds(l));
            audioPlayButton.setImageResource(android.R.drawable.ic_media_play);
            audioPaused = false;

            audioPlayButton.setOnClickListener(v -> {
                if (player != null) {
                    if (!audioPaused) {
                        player.pause();
                        audioPlayButton.setImageResource(android.R.drawable.ic_media_play);
                        duration.stop();
                        audioPaused = true;
                    } else {
                        player.start();
                        audioPlayButton.setImageResource(android.R.drawable.ic_media_play);
                        duration.start();
                        audioPaused = false;
                    }
                    return;
                }

                try {
                    player = MediaPlayer.create(activity, pojo.uri);
                    player.setOnCompletionListener(p -> {
                        player.release();
                        player = null;
                        duration.setBase(SystemClock.elapsedRealtime() - l);
                        duration.stop();
                        audioProgress.setProgress(0);
                        audioPlayButton.setImageResource(android.R.drawable.ic_media_play);
                    });
                    player.start();

                    duration.setBase(SystemClock.elapsedRealtime());
                    duration.start();
                    audioProgress.setProgress(0);
                    duration.setOnChronometerTickListener(c -> audioProgress.setProgress(audioProgress.getProgress() + 1));

                    audioPlayButton.setImageResource(android.R.drawable.ic_media_pause);
                    audioPaused = false;
                } catch (Exception e) {
                    Log.e("Eccles", "Failed to play audio", e);
                }
            });
        }

        /** Stops and releases any {@link MediaPlayer} still attached to this holder. Called both
         *  when this holder is about to be recycled for a different row and defensively at the
         *  start of every {@link #bind}, so a voice note never keeps playing invisibly once its
         *  row has scrolled away or been reused for different data. */
        void stopAudioIfPlaying() {
            if (player != null) {
                try {
                    if (player.isPlaying()) {
                        player.stop();
                    }
                } catch (Exception e) {
                    Log.e("Eccles", "failed to stop recycled audio player", e);
                } finally {
                    player.release();
                    player = null;
                }
            }
            if (duration != null) {
                duration.stop();
            }
        }
    }
}
