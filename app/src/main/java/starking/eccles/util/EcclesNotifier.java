package starking.eccles.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.MajorActivity;
import starking.eccles.bluechat.activities.CallActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.activities.VideoActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.service.Caller;
import starking.eccles.receivers.BSender;
import starking.eccles.bluechat.service.Reader;
import starking.eccles.receivers.CallEnder;

public class EcclesNotifier {

	private static final String C_ID= "Eccles Bluechat";
	public static final String KEY_INPUT= "input";

	/**
	 * Stable per-device notification ID for the "connected" and "new message" notifications.
	 * <p>
	 * Both of those previously shared a single hardcoded ID ({@code 7}), which meant a new
	 * message from one device silently replaced an unread notification for a different device
	 * (or a "device connected" notification) - Android treats {@code notify(id, ...)} calls
	 * with the same ID as updating the same notification slot, not creating a new one. A user
	 * with messages waiting from two different people would only ever see whichever arrived
	 * most recently. Deriving the ID from the device address keeps each conversation's
	 * notification independent (new messages from the same device still correctly update/
	 * replace their own prior notification, which is the desired behavior), while giving
	 * different devices genuinely separate notification slots. {@link #notifyInCall} already
	 * used its own distinct ID ({@code 12}) and is unaffected by this.
	 */
	public static int notificationId(BluetoothDevice d){
		return ("eccles_chat_"+d.getAddress()).hashCode();
	}

	private static void createChannel(String n,String d,Context c){

		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
			NotificationChannel channel= new NotificationChannel("Eccles Bluechat",n,NotificationManager.IMPORTANCE_HIGH);
			channel.setLightColor(Color.BLUE);
			channel.setDescription(d);

			NotificationManager manager= (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
			manager.createNotificationChannel(channel);
		}
	}

	/** Separate notification-ID namespace from {@link #notificationId}'s per-device chat IDs
	 *  and {@code notifyInCall}'s hardcoded 12, so an incoming-call notification can't collide
	 *  with (replace, or be replaced by) either of those for the same device. */
	private static int incomingCallNotificationId(BluetoothDevice d){
		return ("eccles_incoming_call_"+d.getAddress()).hashCode();
	}

	/** Dedicated channel, separate from the shared "Eccles Bluechat" one chat notifications use:
	 *  a channel's sound/vibration is locked in the first time it's created and can't be changed
	 *  afterward except by the user in system settings, so a channel that chat notifications may
	 *  have already created with no special sound could never be retrofitted with a ringtone. */
	private static final String CALL_CHANNEL_ID= "Eccles Incoming Calls";

	private static void createCallChannel(Context c){
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
		NotificationManager manager= (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
		if(manager.getNotificationChannel(CALL_CHANNEL_ID) != null) return;
		NotificationChannel channel= new NotificationChannel(CALL_CHANNEL_ID,"Incoming calls",NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription("Rings for incoming Bluechat voice and video calls");
		channel.setLightColor(Color.BLUE);
		channel.enableVibration(true);
		channel.setVibrationPattern(new long[]{0,1000,1000});
		// USAGE_NOTIFICATION_RINGTONE (as opposed to a plain notification sound) is what makes
		// the system actually loop this for as long as the notification is up, the same way an
		// real incoming call rings, instead of playing once like a text message alert.
		AudioAttributes attrs= new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();
		// Deliberately Settings.System.DEFAULT_RINGTONE_URI here, NOT EcclesStorage.getTone(c):
		// that can return a raw file:// Uri pointing at a custom ringtone the user set (stored
		// under this app's own external files dir). That's fine to hand to a same-process
		// MediaPlayer (as CallActivity/VideoActivity's own in-call ringtone does), but handing a
		// file:// Uri to a notification channel means the *system* notification service - a
		// different process - has to read it to actually play the sound, which is exactly what
		// FileUriExposedException exists to block. This is a very plausible match for "crashes
		// on an incoming call, but only on some devices" - it would only trip for a user who'd
		// actually set a custom ringtone, and exactly which devices treat that as a hard crash
		// (vs. a quieter failure) vs varies by OEM/Android version. The safe system default
		// ringtone avoids the cross-process file access entirely; the in-call screen's own
		// MediaPlayer-based ringtone (same process, so safe) still plays the user's custom tone
		// once that screen is actually up.
		channel.setSound(Settings.System.DEFAULT_RINGTONE_URI,attrs);
		manager.createNotificationChannel(channel);
	}

	/**
	 * Shows an incoming voice/video call, either by jumping straight to the call screen
	 * (foreground) or by posting a ringing, full-screen-intent notification (background).
	 * <p>
	 * This replaces what Reader used to do unconditionally: call {@code context.startActivity
	 * (...)} on SUBTYPE_CALL_REQUEST from its background read loop, every time, regardless of
	 * whether the app was foregrounded. Two separate problems came from that:
	 * <p>
	 * 1) Backgrounded, that direct startActivity() is silently blocked by Android 10+, which
	 * disallows starting an Activity from a process with no visible UI - which is exactly the
	 * state this app is normally in while just sitting connected in the background (kept alive
	 * the whole time by Listener's foreground service, which is why chat notifications still
	 * arrived fine - the process and Reader's read loop were never the problem). The Intent
	 * wasn't rejected loudly, just silently dropped - nothing rang, and call screens
	 * anecdotally seemed to "load minutes later" because reopening the app could finally let an
	 * already-attempted, until-then-invisibly-blocked launch go through.
	 * <p>
	 * 2) Foregrounded, routing every incoming call through a notification instead of straight
	 * to the Activity meant the user had to go find and tap that notification even while
	 * already looking at the app - unnecessary, and also why the caller's own "ringing" status
	 * was delayed: {@code ring()}, which sends SUBTYPE_CALL_RINGING back, only runs once the
	 * receiving side's CallActivity/VideoActivity actually starts, which didn't happen until
	 * that tap.
	 * <p>
	 * The foreground case is simple: start the Activity directly, exactly like before. The
	 * background case now uses a full-screen-intent notification - the one Android-sanctioned
	 * exception to the startActivity restriction, the same mechanism every dialer/VoIP app
	 * relies on - built on its own dedicated, actually-ringing channel so it behaves like a real
	 * incoming call (loops a ringtone, vibrates) instead of sitting there silently until tapped.
	 * Whether the device auto-launches the full-screen Activity immediately (typical when
	 * locked/screen-off) or just shows it as a high-priority heads-up notification (typical
	 * while unlocked and already doing something else - full-screen intents are deliberately
	 * not allowed to hijack the screen in that case) is an Android platform policy this app
	 * can't override, but either way it now actually rings and alerts the user instead of
	 * sitting silent.
	 */
	public static void notifyIncomingCall(Context c,BluetoothDevice d,boolean video){
		// This whole method is wrapped defensively: it runs on the main thread, straight out of
		// Reader's message-handling loop (see performReceive), so any uncaught exception here -
		// an OEM-specific notification/channel quirk, in particular - takes the entire app down
		// with it, which is a far worse failure mode than a missed ring. If the direct-launch
		// path itself fails for some reason, falling through to the notification path still
		// gives the user a way to answer instead of the call silently vanishing.
		try{
			Intent in= new Intent(c,video ? VideoActivity.class : CallActivity.class);
			in.putExtra("device",d);
			in.putExtra("type",CallActivity.TYPE_RECEIVE);
			in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

			if(EcclesActivity.isAppInForeground()){
				c.getApplicationContext().startActivity(in);
				return;
			}

			PendingIntent full= PendingIntent.getActivity(c.getApplicationContext(),
					video ? 16 : 15,in,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

			createCallChannel(c);
			NotificationCompat.Builder b= new NotificationCompat.Builder(c.getApplicationContext(),CALL_CHANNEL_ID);
			b.setContentTitle((video ? "Incoming video call from " : "Incoming call from ")+starking.eccles.util.PeerStore.displayName(d));
			b.setContentText("Tap to answer");
			b.setSmallIcon(video ? R.drawable.ic_video_call : R.drawable.ic_call);
			b.setColor(ContextCompat.getColor(c,R.color.purple_500));
			b.setColorized(true);
			b.setPriority(NotificationCompat.PRIORITY_HIGH);
			b.setCategory(NotificationCompat.CATEGORY_CALL);
			b.setFullScreenIntent(full,true);
			b.setContentIntent(full);
			// Not swipe-dismissible, same reasoning as an ordinary phone call notification -
			// this is cancelled explicitly (cancelIncomingCall) once the call screen actually
			// takes over or the call is no longer pending, not left for the user to swipe away.
			b.setOngoing(true);
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O){
				// Pre-O has no channels to carry the sound/vibration configured above, so set
				// them directly here instead - won't loop the way the O+ channel path does, but
				// still rings and vibrates rather than sitting silent. Same reasoning as
				// createCallChannel's setSound - a safe content/resource Uri, not
				// EcclesStorage.getTone(c)'s potential raw file:// Uri.
				b.setSound(Settings.System.DEFAULT_RINGTONE_URI,AudioManager.STREAM_RING);
				b.setVibrate(new long[]{0,1000,1000});
			}

			NotificationManagerCompat.from(c.getApplicationContext()).notify(incomingCallNotificationId(d),b.build());
		} catch (Exception e){
			Log.e("Eccles","notifyIncomingCall failed - the call may not be answerable this time",e);
		}
	}

	/** Dismisses the notification from {@link #notifyIncomingCall} - call once the call screen
	 *  has actually taken over (so the notification doesn't linger behind it) or once the call
	 *  is no longer pending for any other reason (rejected/ended/unanswered elsewhere). */
	public static void cancelIncomingCall(Context c,BluetoothDevice d){
		try{
			NotificationManagerCompat.from(c.getApplicationContext()).cancel(incomingCallNotificationId(d));
		} catch (Exception e){ Log.e("Eccles","cancelIncomingCall failed",e); }
	}

	public static void notifyConnect(Context c,BluetoothDevice d){
		Intent chat= new Intent(c,ChatActivity.class);
		chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		chat.putExtra("device",d);

		Intent call= new Intent(c,CallActivity.class);
		call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		call.putExtra("device",d);

		Intent video= new Intent(c,VideoActivity.class);
		video.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		video.putExtra("device",d);

		createChannel("Eccles","new connection",c);
		NotificationManagerCompat.from(c.getApplicationContext()).notify(notificationId(d),build("1 new connection",starking.eccles.util.PeerStore.displayName(d)+" connected to you",R.drawable.icon,c,new PendingIntent[]{
			// Android 12+ (API 31) requires every PendingIntent to explicitly declare
			// FLAG_IMMUTABLE or FLAG_MUTABLE; omitting both throws IllegalArgumentException at
			// creation. These three launch an Activity with content this app fully controls
			// (no RemoteInput attached), so FLAG_IMMUTABLE is correct - unlike the RemoteInput
			// reply action below, which genuinely needs FLAG_MUTABLE for the system to fill in
			// the user's typed reply.
			TaskStackBuilder.create(c.getApplicationContext())
					.addNextIntent(new Intent(c.getApplicationContext(),MajorActivity.class))
					.addNextIntent(chat)
					.getPendingIntent(1,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
			PendingIntent.getActivity(c.getApplicationContext(),1,call,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
			PendingIntent.getActivity(c.getApplicationContext(),1,video,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
		},new String[]{"chat","call","video call"},new int[]{
				R.drawable.ic_chat,
				R.drawable.ic_call,
				R.drawable.ic_video_call
		}).build());
	}

	private static NotificationCompat.Builder build(String t,String m,int ic,Context c,PendingIntent[] actions,String[] acname,int[] acic){
		NotificationCompat.Builder b= new NotificationCompat.Builder(c.getApplicationContext(),"Eccles Bluechat");
		b.setContentTitle(t);
		b.setContentText(m);
		b.setShowWhen(true);
		if(ic != 0){
			b.setSmallIcon(ic);
		}
		b.setColor(ContextCompat.getColor(c,R.color.purple_500));
		b.setColorized(true);
		b.setAutoCancel(true);
		b.setPriority(NotificationCompat.PRIORITY_HIGH);
		if((actions != null && acname != null && acic != null) && (actions.length == acname.length) && (acname.length== acic.length)){
			for(int i= 0;i<actions.length;i++){
				b.addAction(acic[i],acname[i],actions[i]);
			}
		}
		return b;
	}

	public static void notifyReceive(Context c,BluetoothDevice d,EcclesMessage m){

		RemoteInput input= new RemoteInput.Builder(KEY_INPUT).setLabel("Reply").build();

		Intent in= new Intent(c,ChatActivity.class);
		in.putExtra("device",d);

		// Explicit back stack: Chats list underneath the conversation. Without a parent, the
		// chat was the only screen in a fresh task, so Back closed the whole app.
		TaskStackBuilder b= TaskStackBuilder.create(c.getApplicationContext());
		b.addNextIntent(new Intent(c.getApplicationContext(),MajorActivity.class));
		b.addNextIntent(in);

		Intent r= new Intent(c.getApplicationContext(),BSender.class);
		r.putExtra("device",d);
		r.putExtra("address",d.getAddress());

		PendingIntent pi= PendingIntent.getBroadcast(c.getApplicationContext(),3,r,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

		NotificationCompat.Action action= new NotificationCompat.Action.Builder(R.drawable.ic_chat,"Reply",pi)
				.setAllowGeneratedReplies(true)
				.addRemoteInput(input)
				.setShowsUserInterface(false)
				.build();

		createChannel("Eccles","new message",c);
		NotificationCompat.Builder nb= build(starking.eccles.util.PeerStore.displayName(d),Reader.getMessage(m,c),R.drawable.icon,c,null,null,null);
		nb.addAction(action).setContentIntent(b.getPendingIntent(0,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

		NotificationManagerCompat.from(c.getApplicationContext()).notify(notificationId(d),nb.build());
	}

	public static Notification notifyInCall(Context c,BluetoothDevice d,int t){

		Intent in= new Intent(c,CallActivity.class);
		in.putExtra("device",d);
		in.putExtra("type",t);

		PendingIntent pi= PendingIntent.getActivity(c,2,in,PendingIntent.FLAG_IMMUTABLE);
		TaskStackBuilder b= TaskStackBuilder.create(c.getApplicationContext());

		b.addNextIntentWithParentStack(in);

		Intent in1= new Intent(c.getApplicationContext(),CallEnder.class);
		in1.putExtra("type",CallActivity.TYPE_ENDED);
		in1.putExtra("device",d);

		PendingIntent pin1= PendingIntent.getBroadcast(c.getApplicationContext(),6,in1,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
		createChannel("Eccles","Ongoin Call",c);

		return build(starking.eccles.util.PeerStore.displayName(d),"ongoing call",R.drawable.ic_call,c,new PendingIntent[]{pin1},new String[]{"End Call"},new int[]{android.R.drawable.ic_menu_call})
				.setContentIntent(b.getPendingIntent(9,PendingIntent.FLAG_IMMUTABLE)).setUsesChronometer(true).build();

	}

	/** Distinct notification ID for {@link #notifyListening}, separate from both the per-device
	 *  IDs {@link #notificationId} produces and {@code notifyInCall}'s hardcoded {@code 12}.
	 *  Public since {@link Listener} needs it for its {@code startForeground} call. */
	public static final int LISTENING_NOTIFICATION_ID= 13;

	/**
	 * Builds the persistent, low-priority notification shown while {@link Listener} is running
	 * as a foreground service and actively listening for incoming Bluetooth connections.
	 * <p>
	 * Unlike the other notifications in this class, this one is intentionally quiet: no sound,
	 * no heads-up popup, minimum importance, marked ongoing (not swipe-dismissible) - it exists
	 * only to satisfy Android's foreground-service requirement that the user always be able to
	 * see that the app is doing something in the background, not to alert them to anything.
	 */
	public static Notification notifyListening(Context c){
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
			NotificationChannel channel= new NotificationChannel("Eccles Bluechat Listening","Listening for connections",NotificationManager.IMPORTANCE_MIN);
			channel.setDescription("Shown while Eccles Bluechat is able to receive incoming connections in the background");
			channel.setShowBadge(false);
			NotificationManager manager= (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
			manager.createNotificationChannel(channel);
		}

		Intent in= new Intent(c,MajorActivity.class);
		in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		PendingIntent pi= PendingIntent.getActivity(c.getApplicationContext(),14,in,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		return new NotificationCompat.Builder(c.getApplicationContext(),"Eccles Bluechat Listening")
				.setContentTitle("Eccles Bluechat")
				.setContentText("Listening for incoming connections")
				.setSmallIcon(R.drawable.icon)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setOngoing(true)
				.setSilent(true)
				.setContentIntent(pi)
				.build();
	}

}