package starking.eccles.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import starking.eccles.Interface.EcclesMessage;
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
		NotificationManagerCompat.from(c.getApplicationContext()).notify(notificationId(d),build("1 new connection",d.getName()+" connected to you",R.drawable.icon,c,new PendingIntent[]{
			// Android 12+ (API 31) requires every PendingIntent to explicitly declare
			// FLAG_IMMUTABLE or FLAG_MUTABLE; omitting both throws IllegalArgumentException at
			// creation. These three launch an Activity with content this app fully controls
			// (no RemoteInput attached), so FLAG_IMMUTABLE is correct - unlike the RemoteInput
			// reply action below, which genuinely needs FLAG_MUTABLE for the system to fill in
			// the user's typed reply.
			PendingIntent.getActivity(c.getApplicationContext(),1,chat,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
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
		in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
		in.putExtra("device",d);

		TaskStackBuilder b= TaskStackBuilder.create(c.getApplicationContext());
		b.addNextIntentWithParentStack(in);

		Intent r= new Intent(c,BSender.class);
		r.putExtra("device",d);

		PendingIntent pi= PendingIntent.getBroadcast(c.getApplicationContext(),3,r,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

		NotificationCompat.Action action= new NotificationCompat.Action.Builder(R.drawable.ic_chat,"Reply",pi)
				.setAllowGeneratedReplies(true)
				.addRemoteInput(input)
				.setShowsUserInterface(true)
				.setContextual(true)
				.build();

		createChannel("Eccles","new message",c);
		NotificationCompat.Builder nb= build(d.getName(),Reader.getMessage(m,c),R.drawable.icon,c,null,null,null);
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

		return build(d.getName(),"ongoing call",R.drawable.ic_call,c,new PendingIntent[]{pin1},new String[]{"End Call"},new int[]{android.R.drawable.ic_menu_call})
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