package starking.eccles.bluechat.service;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.MajorActivity;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.activities.CallActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.bluechat.activities.VideoActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.crypto.EccSession;
import starking.eccles.crypto.KeyChangedException;
import starking.eccles.crypto.SessionManager;
import starking.eccles.data.EcclesData;
import starking.eccles.data.EcclesStorage;
import starking.eccles.util.EcclesNotifier;
import starking.eccles.util.FriendlyDate;

public class Reader {

	/** Per-socket read-loop state. Previously a single running/cancelledIntentionally pair was
	 *  shared across every socket ever passed to {@link #read} (via one CachedThreadPool), so
	 *  cancelling reading for one peer (e.g. starting a call) silently paused reading for every
	 *  other simultaneously-connected peer too, and only the call's own socket was ever resumed
	 *  afterward - other conversations could stop receiving messages indefinitely. Tracking
	 *  state per socket makes cancellation/resumption affect only the intended connection. */
	private static final class SocketState {
		volatile boolean running = true;
		volatile boolean cancelledIntentionally = false;
	}

	private final java.util.concurrent.ConcurrentHashMap<BluetoothSocket, SocketState> states = new java.util.concurrent.ConcurrentHashMap<>();
	public ExecutorService service;
	private Handler handler;
	private final EcclesApplication app;

	private Reader(EcclesApplication app){
		this.app= app;
	}

	public static Reader aquire(Context con){
		EcclesApplication app= (EcclesApplication) con.getApplicationContext();
		if(app.reader != null){
			return app.reader;
		}
		return app.reader= new Reader(app);
	}

	@SuppressLint({"MissingPermission", "NewApi"})
	private String safeDeviceName(BluetoothSocket socket){
		try{
			return starking.eccles.util.PeerStore.displayName(socket.getRemoteDevice());
		} catch (Exception e){
			return "unknown device";
		}
	}

	@SuppressLint({"MissingPermission", "NewApi"})
	public boolean read(BluetoothSocket socket){

		try{

			if(service == null) service= Executors.newCachedThreadPool();
			if(handler == null) handler= new Handler(android.os.Looper.getMainLooper());
			final SocketState state= new SocketState();
			states.put(socket,state);

		service.execute(()->{
			EcclesReader in= null;
			try{
				EccSession session= SessionManager.get(app).obtain(socket);
				in= new EcclesReader(socket.getInputStream(),session);
			} catch (KeyChangedException ke){
				handler.post(()-> {
					if(app.currentActivity != null) app.currentActivity.onIdentityKeyChanged(socket,ke);
				});
				state.running= false;
			} catch (Exception e){
				Log.e("Eccles","secure handshake failed with "+safeDeviceName(socket),e);
				state.running= false;
			}
			while(state.running){
			try{
					if(app.inCall){
						Thread.sleep(100);
						continue;
					}

					EcclesMessage m= EcclesMessage.read(in);

						if(m != null){
							handler.post(()-> performReceive(socket,m.setSender(socket.getRemoteDevice().getAddress()).setDate(FriendlyDate.format())));
						}

				} catch(StreamCorruptedException sc){

					Log.e("Eccles","stream error from reader, disconnecting",sc);
					state.running= false;
					}catch (IOException e){
						Log.e("Eccles","failed to read from "+safeDeviceName(socket),e);
						state.running=false;
				} catch (Exception ee){

					Log.e("Eccles","unknown error occured while reading from "+safeDeviceName(socket)+", disconnecting",ee);
					state.running= false;
				}
			}
			states.remove(socket,state);
			// Only treat this as a real disconnect if the loop wasn't stopped deliberately
			// via cancelReading() (e.g. a call starting) - see the cancelledIntentionally
			// field doc. Pausing for a call must not falsely mark the device as disconnected.
			if(!state.cancelledIntentionally){
				performDisconnect(socket);
			}
		});
		return true;
		} catch (Exception ex){
			return false;
		}
	}

	/**
	 * Stops the read loop for exactly one socket, without affecting any other
	 * simultaneously-connected peer's reading. Used when a call is starting on this socket and
	 * needs exclusive access to its InputStream; see {@link VideoActivity}
	 * and {@link Caller} for the resume side of this handoff.
	 */
	public void cancelReading(BluetoothSocket socket){
		SocketState state= states.get(socket);
		if(state != null){
			state.cancelledIntentionally= true;
			state.running= false;
		}
	}

	/**
	 * Stops reading for every currently-tracked socket. Unlike {@link #cancelReading(BluetoothSocket)}
	 * this is intentionally global - it exists only for full application shutdown
	 * ({@code EcclesApplication#onTerminate}), where stopping everything really is correct,
	 * not the accidental multi-peer side effect the old single-shared-flag design used to have
	 * for every other caller.
	 */
	public void cancelReadingAll(){
		for(SocketState state : states.values()){
			state.cancelledIntentionally= true;
			state.running= false;
		}
		if(service != null){
			service.shutdown();
			service= null;
		}
	}

	public boolean isRunning(BluetoothSocket socket){
		SocketState state= states.get(socket);
		return state != null && state.running;
	}

	public void performReceive(BluetoothSocket s,EcclesMessage m){
		if(s==null || m==null) return;
		try{
			if(s.getRemoteDevice().getBondState()!=android.bluetooth.BluetoothDevice.BOND_BONDED){ Log.w("Eccles","Ignoring message from unpaired device"); return; }
			if(app.getSharedPreferences("Blocked_list",Context.MODE_PRIVATE).getBoolean(s.getRemoteDevice().getAddress(),false)){ Log.w("Eccles","Ignoring message from blocked device"); return; }
		}catch(Exception e){ Log.e("Eccles","Unable to validate Bluetooth peer",e); return; }
		switch (m.type){
			case EcclesMessage.TYPE_CHAT:
				// A message that arrives while its own chat is open on screen is seen right away -
				// counting it would leave a stale unread badge that only cleared on the next visit.
				boolean viewingChat= EcclesActivity.isAppInForeground() && app.currentActivity instanceof ChatActivity
						&& ((ChatActivity)app.currentActivity).device != null
						&& ((ChatActivity)app.currentActivity).device.getAddress().equals(s.getRemoteDevice().getAddress());
				if(!viewingChat) ClassicCompat.newUnread(app,s.getRemoteDevice().getAddress());
				registerUnread(s.getRemoteDevice().getAddress());
				if(m.subtype != EcclesMessage.SUBTYPE_TEXT){
					String path= EcclesStorage.getDir(m.data,app.address!=null && app.address.equals(m.sender),app,m.subtype);
					// getDir() returns null on any local storage/encryption failure (disk full,
					// storage permission revoked, Keystore error, etc.) - path.getBytes() was
					// previously called unconditionally on that result. Since this method runs
					// on the UI thread (see the handler.post() call site), a storage failure on
					// a received audio/image/video message would crash the whole app.
					if(path == null){
						Log.e("Eccles","failed to store received media, dropping message");
						return;
					}
					m.setData(path.getBytes());
				}
				m.setStoreId(app.chatStore.insertRow(ClassicCompat.purifyAddress(s.getRemoteDevice().getAddress()),null,m));
						// getPreview(), not getMessage(): the Chats tab's list preview is only
						// ever seen inside the already-unlocked, already-open app, unlike
						// getMessage()'s deliberately generic lock-screen notification text, so
						// showing the actual latest message here (what every other messenger
						// does) isn't the privacy problem it would be on the lock screen.
						if(!app.chatBase.insert(s.getRemoteDevice(),getPreview(m,app))){
							updateMessage(m);
						}

				if(app.currentActivity instanceof ChatActivity && ((ChatActivity)app.currentActivity).device.getAddress().equals(s.getRemoteDevice().getAddress())){
					((ChatActivity) app.currentActivity).handleMessage(s,m);
				} else {
					EcclesNotifier.notifyReceive(app,s.getRemoteDevice(),m);
				}
				ChatActivity.playTone(app,false);
				break;

			case EcclesMessage.TYPE_CALL:

				if(m.subtype==EcclesMessage.SUBTYPE_CALL_REQUEST){
				// See EcclesNotifier#notifyIncomingCall's doc: a direct startActivity() here is
				// silently blocked by Android whenever the app is backgrounded, which was the
				// entire reason calls never rang/showed anything unless the app happened to
				// already be open.
				EcclesNotifier.notifyIncomingCall(app,s.getRemoteDevice(),false);
				} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_ACCEPTED){
					if(!verifyActivity(CallActivity.TYPE_ACCEPTED)){

					}
				} else if(m.subtype == EcclesMessage.SUBTYPE_CALL_REJECTED){
					if(!verifyActivity(CallActivity.TYPE_REJECTED)){

					}
				} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_UNANSWERED){
					if(!verifyActivity(CallActivity.TYPE_UNANSWERED)){

					}
				} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_RINGING){
					if(!verifyActivity(CallActivity.TYPE_RINGING)){

					}
				}
				break;
			case EcclesMessage.TYPE_VIDEO:
				if(m.subtype==EcclesMessage.SUBTYPE_CALL_REQUEST){
					EcclesNotifier.notifyIncomingCall(app,s.getRemoteDevice(),true);
					} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_ACCEPTED){
					if(!postCall(CallActivity.TYPE_ACCEPTED)){

					}
					} else if(m.subtype == EcclesMessage.SUBTYPE_CALL_REJECTED){
					postCall(CallActivity.TYPE_REJECTED);
				} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_UNANSWERED){
					if(!postCall(CallActivity.TYPE_UNANSWERED)){

					}
				} else if(m.subtype==EcclesMessage.SUBTYPE_CALL_RINGING){
					if(!postCall(CallActivity.TYPE_RINGING)){

					}
				}
				break;
			case EcclesMessage.TYPE_ICON:
				ClassicCompat.saveDeviceIcon(s.getRemoteDevice().getAddress(),m.data,app);
				if(app.currentActivity != null) app.currentActivity.onPeerProfileChanged(s.getRemoteDevice().getAddress());
				break;
			case EcclesMessage.TYPE_PROFILE:
				if(starking.eccles.util.PeerStore.saveAnnounced(s.getRemoteDevice().getAddress(),m.data)
						&& app.currentActivity != null){
					app.currentActivity.onPeerProfileChanged(s.getRemoteDevice().getAddress());
				}
				break;
		}

	}

	public void performDisconnect(BluetoothSocket s){

		EcclesActivity activity= app.currentActivity;
		if(activity==null) return;
		activity.runOnUiThread(()->{
			activity.onDisconnected(s.getRemoteDevice());
		});

		if(activity instanceof SelectActivity){
			activity.runOnUiThread(()->{
				((SelectActivity) activity).processDisconnect(s.getRemoteDevice());
			});

		} else if(activity instanceof ChatActivity){
			activity.runOnUiThread(()->{
				((ChatActivity) activity).performDisconnect(s.getRemoteDevice());
			});

		}
	}

	public void updateMessage(EcclesMessage m){
		EcclesData data= app.chatBase;

		if(!data.updateNewMessage(m.sender,getPreview(m,app))){
			Log.w("Eccles","failed to update new msg from");
		}
	}

	/**
	 * Builds the Chats-tab list preview text for an incoming message - this is only ever shown
	 * inside the app itself (after the user has already unlocked their phone and opened
	 * Bluechat), unlike {@link #getMessage}'s lock-screen notification text, so unlike that one
	 * this can safely show the actual latest message instead of a generic placeholder. Mirrors
	 * {@code ChatActivity#getSent}, which already does the equivalent for messages sent from
	 * this device.
	 */
	public static String getPreview(EcclesMessage m,Context c){
		switch (m.subtype){
			case EcclesMessage.SUBTYPE_TEXT:
				return m.data == null ? "" : new String(m.data,java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+"," ").trim();
			case EcclesMessage.SUBTYPE_AUDIO:
				return "\uD83C\uDFA4 Voice message";
			case EcclesMessage.SUBTYPE_IMAGE:
				return "\uD83D\uDCF7 Photo";
			case EcclesMessage.SUBTYPE_VIDEO:
				return "\uD83D\uDCF9 Video";
			default:
				return null;
		}
	}

	/**
	 * Builds the notification-preview text for an incoming message.
	 * <p>
	 * Text message content is deliberately NOT included here, even though it's already
	 * decrypted at this point - this string is placed directly into the notification body by
	 * EcclesNotifier#notifyReceive, which Android can display on the lock screen. E2EE
	 * transport protects the message in transit and at rest, but doesn't protect it here: if
	 * this returned the plaintext, "end-to-end encrypted" content would be readable by anyone
	 * who can see the phone's lock screen. A sender-only preview matches how other E2EE
	 * messengers handle this by default.
	 */
	public static String getMessage(EcclesMessage m,Context c){

		@SuppressLint("MissingPermission") String d="Someone nearby";
		try {
			android.bluetooth.BluetoothDevice device=((EcclesApplication)c.getApplicationContext()).adapter.getRemoteDevice(m.sender);
			if(device!=null) d=starking.eccles.util.PeerStore.displayName(device);
		} catch(Exception ignored) {}

		switch (m.subtype){
			case EcclesMessage.SUBTYPE_AUDIO:
			return d+" sent you a voice message";
			case EcclesMessage.SUBTYPE_IMAGE:
			return d+" sent you a photo";
			case EcclesMessage.SUBTYPE_TEXT:
			return "New message from "+d;
			case EcclesMessage.SUBTYPE_VIDEO:
			return d+" sent you a video";
			default:
			return null;
		}
	}

	private boolean verifyActivity(short t){

		if(app.currentActivity instanceof CallActivity){
			((CallActivity)app.currentActivity).handleCall(t);
			return true;
		}
		return false;
	}

	private boolean postCall(short t){
		EcclesActivity act= app.currentActivity;

		if(act instanceof VideoActivity){
			((VideoActivity)act).handleCall(t);
			return true;
		}
		return false;
	}

	public void registerUnread(String add){
		EcclesActivity act= app.currentActivity;
		if(act instanceof MajorActivity){
			if(((MajorActivity)act).fragment != null) ((MajorActivity)act).fragment.checkUnread(add);
			((MajorActivity)act).checkStatus();
		}
	}

}