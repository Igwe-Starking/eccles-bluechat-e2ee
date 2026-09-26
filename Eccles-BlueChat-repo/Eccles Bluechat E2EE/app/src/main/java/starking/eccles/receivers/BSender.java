package starking.eccles.receivers;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import java.util.concurrent.Executors;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.util.EcclesNotifier;

public class BSender extends EcclesReceiver {

	@Override
	public void onReceive(Context c,Intent in){
		android.os.Bundle results= RemoteInput.getResultsFromIntent(in);
		CharSequence msg= results != null ? results.getCharSequence(EcclesNotifier.KEY_INPUT) : null;
		android.os.Bundle extras= in.getExtras();
		BluetoothDevice d= extras != null ? extras.getParcelable("device") : null;

		if(msg == null || msg.length()==0 || d == null){
			removeNot("Failed: empty message",c,d);
			return;
		}

		final Context appContext= c.getApplicationContext();
		final PendingResult pending= goAsync();
		Executors.newSingleThreadExecutor().execute(()->{
			try{
				EcclesApplication ea= (EcclesApplication) appContext;
				EcclesMessage m= new EcclesMessage(EcclesMessage.TYPE_CHAT,EcclesMessage.SUBTYPE_TEXT).setData(msg.toString().getBytes());
				BluetoothSocket s= ea.getSocket(d.getAddress());

				if(s == null){
					removeNot("Failed: "+d.getName()+" is not active",appContext,d);
					return;
				}

				// Always use the synchronous writeHeadless() path, regardless of whether an
				// activity is currently in the foreground. The previous currentActivity-present
				// branch posted an asynchronous write() to the UI thread (fire-and-forget, no
				// completion signal) and immediately reported "successful" and persisted the
				// message as sent on this background thread, before the actual send had even
				// been attempted - a real socket/encryption failure occurring moments later
				// would leave the notification saying "sent" and the local history saying
				// "sent" for a message that was never actually delivered. writeHeadless() does
				// its own session/writer setup independently of currentActivity and returns the
				// real, synchronous result of the socket write, so success is only ever
				// reported once it has genuinely happened. The one tradeoff: if the chat screen
				// for this exact conversation happens to already be open, its live view won't
				// be updated in real time by this reply (it will show correctly next time that
				// screen loads/refreshes) - a minor UI nicety, not a correctness issue, and a
				// reasonable trade for never lying about whether the message actually sent.
				if(ea.writeHeadless(s,m)){
					ea.chatStore.insert(ClassicCompat.purifyAddress(d.getAddress()),"sent",m);
					removeNot("successful",appContext,d);
				} else {
					removeNot("Failed to send message",appContext,d);
				}
			} catch(Exception e){
				android.util.Log.e("BSender","quick-reply send failed",e);
				removeNot("Failed to send message",appContext,d);
			} finally {
				pending.finish();
			}
		});
	}

	private void removeNot(String r,Context c,BluetoothDevice d){
		if(r != null){
			android.os.Handler main= new android.os.Handler(android.os.Looper.getMainLooper());
			main.post(()-> Toast.makeText(c,r,Toast.LENGTH_SHORT).show());
		}
		// Cancel just this conversation's notification, matching the per-device ID
		// EcclesNotifier now uses - previously this called cancelAll(), which cleared every
		// notification in the tray (any other unread conversation's notification, or
		// anything else notified), not just the one being replied to. When the device is
		// unknown (the empty-message early-exit path above), there's no specific notification
		// ID to target, so this still falls back to cancelAll() only in that narrow case.
		if(d != null){
			NotificationManagerCompat.from(c).cancel(EcclesNotifier.notificationId(d));
		} else {
			NotificationManagerCompat.from(c).cancelAll();
		}
	}
}
