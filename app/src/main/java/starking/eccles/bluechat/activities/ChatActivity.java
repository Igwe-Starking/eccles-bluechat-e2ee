package starking.eccles.bluechat.activities;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;
import android.widget.TextView;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.widget.PopupWindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.Surface.ChatAdapter;
import starking.eccles.Surface.ChatPojo;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.MajorActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.SelectActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.service.Reader;
import starking.eccles.data.EcclesStorage;
import starking.eccles.util.EcclesIcon;
import starking.eccles.util.FriendlyDate;

public class ChatActivity extends EcclesActivity {

	private BluetoothSocket socket;
	public BluetoothDevice device;
	private ImageView pic,file;
	private FloatingActionButton send;
	private ScheduledExecutorService intermittent;
	private CardView cardView;
	private EditText input;
	private Chronometer recDur;
	private MediaRecorder recorder;
	private File recOut;
	public ChatAdapter adapter;
	public RecyclerView chatList;
	private boolean buttonActivated= false,isRead= false;

	public static final int CAMERA_CONTENT= 12;
	public static final int CARM_CONTENT= 13;
	public static final int PIC_CONTENT= 14;
	public static final int VIDEO_CONTENT= 15;
	public static final int AUDIO_CONTENT= 16;

	private static String VIDEO_TEMP_DIR;
	private static String IMAG_TEMP_DIR;
	private String AUDIO_TEMP_DIR;
	public ArrayList<ChatPojo> selections;
	public boolean selecting= false;
	private ActionMode mode;

	@Override
	public void onCreate(Bundle bundle){
		setContentView(R.layout.activity_chat);
		device= getIntent().getExtras()!=null ? getIntent().getExtras().getParcelable("device") : null;
		if(device == null){
			Toast.makeText(this,"Couldn't open this chat. Please pick the device again.",Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		Toolbar toolbar= findViewById(R.id.chat_tools);
		setSupportActionBar(toolbar);
		getSupportActionBar().setTitle(starking.eccles.util.PeerStore.displayName(device));
		getSupportActionBar().setIcon(EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		pic= findViewById(R.id.picture);
		file= findViewById(R.id.carm);
		send= findViewById(R.id.send_bt);
		cardView= findViewById(R.id.input_parent);
		input= findViewById(R.id.input);
		// Bring back whatever was typed but never sent the last time this chat was open.
		String savedDraft= starking.eccles.util.DraftStore.get(this,device.getAddress());
		if(savedDraft != null && input.getText().length() == 0) input.setText(savedDraft);
		recDur= findViewById(R.id.rec_dur);
		chatList= findViewById(R.id.chat_list);

		LinearLayoutManager lm= new LinearLayoutManager(this);
		lm.setStackFromEnd(true);
		chatList.setLayoutManager(lm);
		chatList.setTop(getSupportActionBar().getHeight()+10);

		AUDIO_TEMP_DIR= getFilesDir().getAbsolutePath()+File.separator+"tmp_audios";

		super.onCreate(bundle);

		socket= app.getSocket(device.getAddress());

	}

	@Override
	public void onResume(){

		super.onResume();
	}

	@Override
	public void onStart(){

		String sbt= app.isConnected(device.getAddress()) ? "active" : "offline";
		updateStatus(sbt);
		ClassicCompat.clearUnreads(this,device.getAddress());
		if(adapter == null) adapter= new ChatAdapter(this);

		// Guarded, not unconditional: this is the actual reason returning from viewing an
		// image/video always landed back at the newest message, even if the viewed image was
		// right at the top of a long scrolled-up chat. onStart() (unlike onCreate()) re-runs
		// every time this Activity resumes, including right after ViewActivity finishes and
		// control returns here - and RecyclerView#setAdapter(), even with the exact same
		// adapter instance, resets the layout manager's scroll position back to its initial
		// state (the bottom, since chatList uses setStackFromEnd(true)). Only actually calling
		// it the first time leaves whatever scroll position the user had alone.
		if(chatList.getAdapter() != adapter){
			chatList.setAdapter(adapter);
		}
		if(!isRead){
		try{
			app.chatStore.readAll(this,device.getAddress());
			isRead= true;
		} catch (Exception e){ android.util.Log.e("ChatActivity", "Suppressed exception", e); }
		}
		input.addTextChangedListener(new TextWatcher(){
			@Override
			public void beforeTextChanged(CharSequence cs,int s,int a,int c){

			}
			public void afterTextChanged(Editable e){

			}
			public void onTextChanged(CharSequence cs,int s,int c,int e){
				if(cs != null && cs.length()>0){
					toggleButtonIcon(true);
				} else {
					toggleButtonIcon(false);
				}
			}
		});

		recBar= findViewById(R.id.rec_bar);
		recHint= findViewById(R.id.rec_hint);
		lockHint= findViewById(R.id.lock_hint);
		recCancelBtn= findViewById(R.id.rec_cancel);
		recCancelBtn.setOnClickListener(v-> cancelRecording());

		// WhatsApp-style voice notes: hold to record, release to send, slide left to cancel,
		// slide up to lock (keeps recording hands-free; then tap send, or the trash to discard).
		// With text in the box this returns false so the normal click (send text) runs instead.
		send.setOnTouchListener((View v,MotionEvent mt)->{
			float density= getResources().getDisplayMetrics().density;
			switch(mt.getActionMasked()){
				case MotionEvent.ACTION_DOWN:
					if(recorder != null && recLocked){
						// Recording is locked: this tap is the "send" press.
						recGesture= true;
						return true;
					}
					if(input.getText() != null && input.getText().length() > 0) return false;
					recorder= recordVoice("Eccles123");
					if(recorder == null) return true; // permission dialog showing, or failed to start
					recLocked= false;
					recGesture= true;
					recDownX= mt.getRawX();
					recDownY= mt.getRawY();
					recStart= SystemClock.elapsedRealtime();
					showRecordingUi();
					if(v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
					return true;

				case MotionEvent.ACTION_MOVE:
					if(!recGesture || recorder == null || recLocked) return recGesture;
					float dx= mt.getRawX() - recDownX;
					float dy= recDownY - mt.getRawY();
					if(dy > 70 * density){
						lockRecording();
					} else if(dx < -90 * density){
						recGesture= false;
						cancelRecording();
					} else {
						recHint.setAlpha(Math.max(0.2f,1f + dx / (90 * density)));
					}
					return true;

				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if(!recGesture) return false;
					recGesture= false;
					if(recorder == null) return true;
					if(recLocked){
						// Lock happened during this very touch (finger lifting after the slide-up):
						// keep recording. A later tap on the button is what sends.
						if(recLockedThisTouch){ recLockedThisTouch= false; return true; }
						if(mt.getActionMasked() == MotionEvent.ACTION_UP) sendRecording();
						return true;
					}
					if(mt.getActionMasked() == MotionEvent.ACTION_CANCEL){
						cancelRecording();
					} else if(SystemClock.elapsedRealtime() - recStart < 600){
						cancelRecording();
						Toast.makeText(this,"Hold the mic button to record, release to send",Toast.LENGTH_SHORT).show();
					} else {
						sendRecording();
					}
					return true;
			}
			return false;
		});

		send.setOnClickListener((View view)->{
			String text = input.getText().toString();
			if (!text.isEmpty()) {
				sendMessage(EcclesMessage.SUBTYPE_TEXT, text.getBytes(StandardCharsets.UTF_8));
				input.setText(null);
			}
		});

		if("offline".equals(sbt)){
			toggleInputButton(false);
		} else {
			activateButton();
			buttonActivated= true;
		}
		// Unconditional now, not just when starting out offline: this used to only ever run as
		// a reconnect-retry loop for a chat that was already offline when opened, so a chat
		// that went from active to actually disconnected *while open* had no periodic fallback
		// at all - only the onConnectionFailed/performDisconnect callbacks (which are correct
		// now, see their comments about BluetoothDevice reference-vs-address equality, but this
		// stays as a safety net in case anything else ever slips past them) could catch it, and
		// if those ever missed it, the status just stayed stale until you left and came back.
		startIntermittent();

		super.onStart();

		EcclesActivity act= (EcclesActivity) getParent();
		if(act != null && act instanceof MajorActivity){
			((MajorActivity)act).checkStatus();
			} else if(act != null && act instanceof SelectActivity && ((SelectActivity)act).getParent() instanceof MajorActivity){
			((MajorActivity)((SelectActivity)act).getParent()).checkStatus();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		m.add("Voice call").setIcon(R.drawable.ic_call).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		m.add("Video call").setIcon(R.drawable.ic_video_call).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		m.add(app.isConnected(device.getAddress()) ?"Disconnect" : "Connect");
		m.add(block(device.getAddress(),BLOCK_CHECK_BLOCK)? "Unblock":"Block");
		m.add("Rename contact");
		m.add("Clear chat");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId() == android.R.id.home){
			super.onBackPressed();
			return false;
		}

		switch (item.getTitle().toString()){
			case "Voice call":
				call(false);
				return false;
			case "Video call":
				call(true);
				return false;
			case "Disconnect":
				disconnect(device,true);
				return false;
			case "Block":
				block(device.getAddress(),BLOCK_BLOCK);
				return false;
			case "Rename contact":
				renamePeer(device);
				return false;
			case "Clear chat":
				clear();
				return false;
			case "Unblock":
				block(device.getAddress(),BLOCK_UNBLOCK);
				return false;
			case "Connect":
				connect(device,true);
		}
		return super.onOptionsItemSelected(item);
	}

	private void startIntermittent(){
		if(intermittent == null){
			intermittent= Executors.newScheduledThreadPool(1);
			intermittent.scheduleAtFixedRate(()->{
				boolean connected= app.isConnected(device.getAddress());
				runOnUiThread(()->{
					if(!connected){
						updateStatus("offline");
						getSupportActionBar().setSubtitle("Connecting\u2026");
						connect(device,false);
					} else {
						updateStatus("active");
					}
				});
			},10,10,TimeUnit.SECONDS);
		}
	}

	private void stopIntermittent(){
		if(intermittent != null){
			intermittent.shutdown();
			intermittent= null;
		}
	}

	@Override
	public void onStop(){
		stopIntermittent();
		if(recorder != null) cancelRecording();
		saveDraft();
		super.onStop();
	}

	/** Keeps unsent text as a draft: shown in the Chats tab and restored into the box next
	 *  time this chat opens. Cleared once the box is empty (i.e. after sending). */
	private void saveDraft(){
		try{
			if(input == null || device == null) return;
			String text= input.getText() == null ? null : input.getText().toString();
			String address= device.getAddress();
			boolean has= text != null && !text.trim().isEmpty();
			starking.eccles.util.DraftStore.set(this,address,text);

			// The Chats tab only lists contacts that have a chat row, so a brand-new chat with
			// just a draft needs one (empty) to show "Draft" under; remove it again if the draft
			// is cleared before anything was ever sent.
			starking.eccles.Surface.EcclesPojo existing= null;
			starking.eccles.Surface.EcclesPojo[] rows= app.chatBase.read(starking.eccles.Surface.EcclesPojo.CHAT_CLIENT);
			if(rows != null){
				for(starking.eccles.Surface.EcclesPojo r: rows){
					if(r != null && address.equals(r.tag)){ existing= r; break; }
				}
			}
			if(has && existing == null){
				app.chatBase.insert(device,"");
			} else if(!has && existing != null && existing.status != null && existing.status.startsWith("\b")){
				app.chatBase.delete(address);
			}
		} catch(Exception e){
			Log.e("ChatActivity","saveDraft failed",e);
		}
	}

	@Override
	public void onConnectionFailed(BluetoothDevice device,String r){
		runOnUiThread(()->{
		// BluetoothDevice doesn't override equals()/hashCode(), so it falls back to reference
		// identity - this used to be "==", which meant it only ever matched if `device` here
		// happened to be the literal same object instance as this.device (set once, from the
		// launching Intent's extra). A device handed back from anywhere else - Reader's socket,
		// a fresh connection attempt, etc. - is a distinct Parcelable-reconstructed object for
		// the same physical device, so this essentially never actually matched, and the status
		// label just silently never updated to "offline". Comparing by address is the correct,
		// standard way to check "is this the same physical device".
		if(this.device != null && device != null && this.device.getAddress().equals(device.getAddress())){
			updateStatus("offline");
		}
		});
	}

	@Override
	public void onConnected(BluetoothSocket socket){
		runOnUiThread(()->{
		if(socket.getRemoteDevice() != null && this.device != null && socket.getRemoteDevice().getAddress().equals(this.device.getAddress())){
			updateStatus("active");
			toggleInputButton(true);

			if(!buttonActivated){
				activateButton();
				buttonActivated= true;
			}
		}
		});
		super.onConnected(socket);
	}

	private void toggleButtonIcon(boolean sen){
		int ic= sen ? R.drawable.ic_send : R.drawable.ic_mic;
		send.setImageResource(ic);
	}

	private void toggleInputButton(boolean show){
		runOnUiThread(()->{
		int v= show? View.VISIBLE : View.INVISIBLE;
		cardView.setVisibility(v);
		send.setVisibility(v);
		});
	}

	private PopupWindow registerPopup(View v,int r){
		final PopupWindow window= new PopupWindow(this);
		window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		window.setContentView(getLayoutInflater().inflate(r,null));
		window.setOutsideTouchable(true);
		window.setFocusable(true);
		window.setAnimationStyle(android.R.style.Animation_Dialog);
		v.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
		window.getContentView().measure(0,0);
		v.setOnClickListener((View v1)->{
			window.showAsDropDown(v1,-20,-v1.getMeasuredHeight()-window.getContentView().getMeasuredHeight());
		});

		return window;
	}

	private void activateButton(){
		if(Build.VERSION.SDK_INT>=23){
		final PopupWindow w1= registerPopup(pic,R.layout.bt_open_camera);
		final PopupWindow w2= registerPopup(file,R.layout.bt_open_carm);

		ImageView openCamera= w1.getContentView().findViewById(R.id.bt_open_camera);
		ImageView openVideo= w1.getContentView().findViewById(R.id.bt_open_carm);

		ImageView selectPic= w2.getContentView().findViewById(R.id.bt_open_pic);
		ImageView selectVid= w2.getContentView().findViewById(R.id.bt_select_video);
		ImageView selectAud= w2.getContentView().findViewById(R.id.bt_select_audio);

		openCamera.setOnClickListener((View view)->{
			w1.dismiss();
			openCarm(MediaStore.ACTION_IMAGE_CAPTURE,CAMERA_CONTENT);
		});

		openVideo.setOnClickListener((View view)->{
			w1.dismiss();
			openCarm(MediaStore.ACTION_VIDEO_CAPTURE,CARM_CONTENT);
		});

		selectPic.setOnClickListener((View view)->{
			w2.dismiss();
			selectStuff("image/*",PIC_CONTENT);
		});

		selectVid.setOnClickListener((View view)->{
			w2.dismiss();
			selectStuff("video/*",VIDEO_CONTENT);
		});

		selectAud.setOnClickListener((View view)->{
			w2.dismiss();
			selectStuff("audio/*",AUDIO_CONTENT);
		});
		} else {
			PopupMenu pm= new PopupMenu(this,pic);
			PopupMenu fm= new PopupMenu(this,file);

			pm.setOnMenuItemClickListener((MenuItem item)->{
				switch(item.getItemId()){
					case R.id.image:
						openCarm(MediaStore.ACTION_IMAGE_CAPTURE,CAMERA_CONTENT);
						break;

					case R.id.video:
						openCarm(MediaStore.ACTION_VIDEO_CAPTURE,CARM_CONTENT);
						break;
				}
				return true;
			});
			fm.setOnMenuItemClickListener((MenuItem item)->{
				switch(item.getItemId()){
					case R.id.image:
						selectStuff("image/*",PIC_CONTENT);
						break;
					case R.id.video:
						selectStuff("video/*",VIDEO_CONTENT);
						break;
					case R.id.audio:
						selectStuff("audio/*",AUDIO_CONTENT);
						break;
				}
				return true;
			});
			pm.inflate(R.menu.camera_menu);
			fm.inflate(R.menu.gallery_menu);

			pic.setOnClickListener((View v)->{
				pm.show();
			});
			file.setOnClickListener((View v)->{
				fm.show();
			});
		}
	}

	private String pendingCarmAction;
	private int pendingCarmRc;

	private void openCarm(String w,int rc){
		// The manifest declares CAMERA, and Android refuses to start the camera app's capture
		// screen on our behalf (SecurityException, which crashed the app) while that
		// permission is declared but not granted/revoked - so it has to be granted first.
		if(!requestPermission(new String[]{Manifest.permission.CAMERA},CAMERA_PERM)){
			pendingCarmAction= w;
			pendingCarmRc= rc;
			return;
		}
		try{
			Intent in= new Intent(w);
			ActivityCompat.startActivityForResult(this,in,rc,null);
		} catch(Exception e){
			Log.e("ChatActivity","unable to open camera",e);
			Toast.makeText(this,"Unable to open the camera",Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public void onRequestPermissionsResult(int rc,String[] perm,int[] gr){
		super.onRequestPermissionsResult(rc,perm,gr);
		if(rc == CAMERA_PERM){
			String action= pendingCarmAction;
			pendingCarmAction= null;
			if(action != null){
				if(gr.length > 0 && gr[0] == android.content.pm.PackageManager.PERMISSION_GRANTED){
					openCarm(action,pendingCarmRc);
				} else {
					Toast.makeText(this,"Camera permission is needed to take photos or videos",Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	private void selectStuff(String s,int rc){
		Intent in= new Intent();
		in.setType(s);
		in.setAction(Intent.ACTION_GET_CONTENT);
		ActivityCompat.startActivityForResult(this,in,rc,null);
	}

	private View recBar,lockHint,recCancelBtn;
	private TextView recHint;
	private boolean recLocked= false,recGesture= false,recLockedThisTouch= false;
	private float recDownX,recDownY;
	private long recStart;

	private void showRecordingUi(){
		cardView.setVisibility(View.INVISIBLE);
		recBar.setVisibility(View.VISIBLE);
		recHint.setVisibility(View.VISIBLE);
		recHint.setAlpha(1f);
		recCancelBtn.setVisibility(View.GONE);
		lockHint.setVisibility(View.VISIBLE);
	}

	private void hideRecordingUi(){
		recBar.setVisibility(View.GONE);
		lockHint.setVisibility(View.GONE);
		recCancelBtn.setVisibility(View.GONE);
		cardView.setVisibility(View.VISIBLE);
		recLocked= false;
		recLockedThisTouch= false;
		toggleButtonIcon(input.getText() != null && input.getText().length() > 0);
	}

	private void lockRecording(){
		recLocked= true;
		recLockedThisTouch= true;
		lockHint.setVisibility(View.GONE);
		recHint.setVisibility(View.GONE);
		recCancelBtn.setVisibility(View.VISIBLE);
		toggleButtonIcon(true); // send arrow: tapping it now sends the recording
		send.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
	}

	private void sendRecording(){
		if(recorder == null) return;
		File u= getVoice(recorder);
		recorder= null;
		hideRecordingUi();
		try{
			byte[] b= Files.readAllBytes(u.toPath());
			sendMessage(EcclesMessage.SUBTYPE_AUDIO,b);
		} catch (Exception e){
			Log.e("Eccles","invalid file",e);
			Toast.makeText(this,"Couldn't send the voice message. Please try again.",Toast.LENGTH_SHORT).show();
		}
	}

	private void cancelRecording(){
		if(recorder != null){
			File u= getVoice(recorder);
			recorder= null;
			try{ if(u != null) u.delete(); } catch(Exception ignored){}
		}
		hideRecordingUi();
	}

	public MediaRecorder recordVoice(String n){
		try{
		if(requestPermission(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERM)){
				MediaRecorder recorder= new MediaRecorder();
				recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
				recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
				recorder.setAudioEncoder(MediaRecorder.OutputFormat.AMR_NB);

				File fold = new File(AUDIO_TEMP_DIR+File.separator+n);
				if(fold.exists() || fold.mkdirs()){

					recOut= File.createTempFile(n,".tmp",fold);
					recorder.setOutputFile(recOut);
					recorder.prepare();

					recorder.start();

					recDur.setVisibility(View.VISIBLE);
					recDur.setBase(SystemClock.elapsedRealtime());
					recDur.start();

					return recorder;

				}
		}
		} catch (Exception e){
			Log.e("Eccles","failed to prepare recorder",e);
		}
		return null;
	}

	private File getVoice(MediaRecorder r){
		try{
			r.stop();
			r.release();
		} catch(Exception e){
			Log.e("Eccles","failed to stop recorder",e);
		}
		recDur.stop();
		recDur.setBase(SystemClock.elapsedRealtime());
		recDur.setVisibility(View.GONE);
		recDur.stop();

		return recOut;
	}

	public static void playTone(Context con,boolean sent){
		int t= sent ? R.raw.sent_tone : R.raw.received;
		MediaPlayer tonePlayer= MediaPlayer.create(con,t);
		tonePlayer.setOnCompletionListener((MediaPlayer p)->{
			try{
				p.release();
				p= null;
			} catch(Exception e){ android.util.Log.e("ChatActivity", "Suppressed exception", e); }
		});
		tonePlayer.start();
	}

	@Override
	public void sending(BluetoothSocket s,EcclesMessage message){
		if(s.getRemoteDevice().getAddress().equals(device.getAddress())){
		if(message.type != EcclesMessage.TYPE_CHAT) return;
		EcclesMessage m= message.clone();
		if(m.subtype != EcclesMessage.SUBTYPE_TEXT){
			String storedPath= EcclesStorage.getDir(message.data,true,this,message.subtype);
			if(storedPath == null){
				Log.e("Eccles","failed to store outgoing media locally, aborting send");
				Toast.makeText(this,"Couldn't save the file, so it wasn't sent",Toast.LENGTH_SHORT).show();
				return;
			}
			m.setData(storedPath.getBytes());
		} else {
			m.setData(message.data);
		}
		m.setDate(FriendlyDate.format());
		try{
			m.setStoreId(app.chatStore.insertRow(ClassicCompat.purifyAddress(device.getAddress()),"",m));
			if(m.storeId == -1){
				// insert() now correctly fails (returns false) rather than silently storing an
				// empty row when at-rest encryption fails - see ChatBase#insert. The message
				// still gets sent over the wire regardless (that's a separate, already-in-flight
				// operation), but it won't survive an app restart if this failed, so at least
				// log it for diagnosability rather than silently losing local history.
				Log.w("Eccles","failed to persist locally-sent message for "+starking.eccles.util.PeerStore.displayName(device));
			}
		} catch (Exception e){ android.util.Log.e("ChatActivity", "Suppressed exception", e); }
		if(!app.chatBase.insert(device,getSent(m,this))){
			if(!app.chatBase.updateNewMessage(device.getAddress(),getSent(m,this))){
				Log.w("Eccles","failed to update message sent to "+starking.eccles.util.PeerStore.displayName(device));
			}
		}

		ChatPojo p= new ChatPojo(m,this);
		p.setStatus("sending");
		updateAdapter(p);
		}
	}
	@Override
	public void sent(BluetoothSocket s,EcclesMessage m){
		// See EcclesMessage#localId's doc: this used to look up by m.sender, which is always
		// null for an outgoing message - the lookup could never match, so this whole block
		// (including persisting "sent" to chatStore) silently never ran, and the row stayed on
		// "sending" forever regardless of how long you waited.
		ChatPojo p= adapter.findPojoWithTag(String.valueOf(m.localId));
		if(p != null){
			p.setStatus("sent");
			adapter.notifyDataSetChanged();
			app.chatStore.updateStatus(device.getAddress(),"sent",p.index);
		}
		playTone(this,true);
		super.sent(s,m);
	}
	@Override
	public void sentFailed(BluetoothSocket s,EcclesMessage m,String r){
		ChatPojo p= adapter.findPojoWithTag(String.valueOf(m.localId));
		if(p != null){
			p.setStatus("failed");
			adapter.notifyDataSetChanged();
			app.chatStore.updateStatus(device.getAddress(),"failed",p.index);
		}
		Log.w("Eccles","failed to send message to "+starking.eccles.util.PeerStore.displayName(s.getRemoteDevice())+"\n"+r);
	}

	private void updateAdapter(ChatPojo p){
		adapter.add(p);
		// Mirrors the old TRANSCRIPT_MODE_ALWAYS_SCROLL behavior: always jump to the newest
		// message when one is sent or received.
		chatList.scrollToPosition(adapter.pojos.size()-1);
	}

	@Override
	public void onActivityResult(final int rc,final int res,final Intent in){
		super.onActivityResult(rc,res,in);
		Uri u= null;
		try{
			u= in.getData();
		} catch (Exception fe){
			Toast.makeText(this,"That file could not be found",Toast.LENGTH_SHORT).show();
			return;
		}
		if(u== null) return;
		if(!EcclesStorage.verifySize(this,u) && !app.pref.getBoolean("send_large",false)){
			String n= EcclesStorage.getDisplayName(this,u);
			long sizeBytes= EcclesStorage.getFileSize(this,u);
			String s= sizeBytes>=0 ? Formatter.formatShortFileSize(this,sizeBytes) : "unknown";
			SpannableStringBuilder b= new SpannableStringBuilder()
					.append("This is a large file\n",new ForegroundColorSpan(Color.RED),0)
					.append("Files over 3 MB can take a long time over Bluetooth, and chats and calls with this device may be slow until it finishes.\nIf the other person moves out of range while it is sending, the file won't arrive.\nFor big files, a file-sharing app works better.\n\n")
					.append("File: "+n+"\nSize: "+s,new ForegroundColorSpan(Color.WHITE),0);

			final Uri u1= u;
			if(app.pref.getBoolean("no_alert",false)){
				// showDialog() deliberately shows nothing at all when alerts are turned off, so
				// its "Send" callback would never run and the file would just silently vanish
				// with no feedback - exactly the sort of silent failure that should be reported.
				Toast.makeText(this,"This file is over 3 MB, so it wasn't sent. Turn alerts back on in Settings to confirm large files.",Toast.LENGTH_LONG).show();
				return;
			}
			showDialog(b.toString(),"Send anyway","Cancel","Don't ask again",()->{
				// `dialog`, not `progressDialog`: showDialog() above is what creates and stores
				// this confirmation dialog (and its "Always Send" checkbox). progressDialog is a
				// completely different field, only ever assigned by showProgressDialog(), so
				// here it was null - the "null object reference" crash on sending a large file.
				// EcclesApplication already reads it the correct way (activity.dialog.checked).
				if(dialog != null && dialog.checked){
					app.pref.edit().putBoolean("send_large",true).apply();
				}
				sendData(u1,rc,res);
			},null);
			return;
		}
		sendData(u,rc,res);
	}

	private void sendData(Uri u,int rc,int res){
		try{
			if((rc == CAMERA_CONTENT || rc == PIC_CONTENT) && res == RESULT_OK){
				sendMessage(EcclesMessage.SUBTYPE_IMAGE,EcclesStorage.getCompressedImageBytes(u,this));
				} else if((rc== CARM_CONTENT || rc == VIDEO_CONTENT) && res== RESULT_OK){
				sendMessage(EcclesMessage.SUBTYPE_VIDEO,EcclesStorage.getBytes(u,this));
				} else if(rc == AUDIO_CONTENT && res== RESULT_OK){
				sendMessage(EcclesMessage.SUBTYPE_AUDIO,EcclesStorage.getBytes(u,this));
			}
			} catch (Exception e){
			// This used to only log - the far more common failure path (a regular Exception:
			// a failed file read, a permission issue, etc.) had no user-facing feedback of any
			// kind, only the much rarer OutOfMemoryError case below did. Matches "all uploads"
			// silently failing with no alert.
			Log.e("Eccles","file send failed",e);
			Toast.makeText(this,"Couldn't send the file. Please try again.",Toast.LENGTH_LONG).show();
		} catch (OutOfMemoryError oe){
			Toast.makeText(this,"This file is too large to send",Toast.LENGTH_SHORT).show();
		}
	}
	private void sendMessage(int st,byte[] d){
		EcclesMessage m= new EcclesMessage(EcclesMessage.TYPE_CHAT,st).setData(d);
		write(socket,m);
	}

	public void handleMessage(BluetoothSocket s,EcclesMessage m){
			if(s.getRemoteDevice().getAddress().equals(device.getAddress())){
			ChatPojo p= new ChatPojo(m,this);
			updateAdapter(p);
		}
	}

	@Override
	public void onPeerProfileChanged(String address){
		if(device != null && device.getAddress().equals(address) && getSupportActionBar() != null){
			getSupportActionBar().setTitle(starking.eccles.util.PeerStore.displayName(device));
			getSupportActionBar().setIcon(EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));
		}
	}

	public void performDisconnect(BluetoothDevice d){
		// Same reference-vs-address-equality issue as onConnectionFailed/onConnected above.
		if(this.device != null && d != null && this.device.getAddress().equals(d.getAddress())) toggleInputButton(false);
		onConnectionFailed(d,null);
	}

	public void select(final ChatPojo cp){

		if(!selecting){
			mode= startSupportActionMode(new ActionMode.Callback(){
				@Override
				public boolean onCreateActionMode(ActionMode m,Menu mn){

					selecting= true;
					selections= new ArrayList();
					if(!cp.isSelected)selections.add(cp);
					cp.setSelected(true);

					m.setTitle(selections.size()+" selected");

					MenuItem it1= mn.add("Delete");
					it1.setIcon(android.R.drawable.ic_menu_delete);
					it1.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

					MenuItem it2= mn.add("Cancel");
					it2.setIcon(android.R.drawable.ic_delete);
					it2.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

					return true;
				}

				@Override
				public boolean onPrepareActionMode(ActionMode m,Menu mn){
					return false;
				}

				@Override
				public void onDestroyActionMode(ActionMode m){
					selecting= false;

					if(selections.size()>0){
						for(ChatPojo scp:selections){
							scp.setSelected(false);
						}
					}
					selections= null;
					m= null;
				}

				@Override
				public boolean onActionItemClicked(ActionMode m,MenuItem it){
					switch(it.getTitle().toString()){
						case "Delete":
							if(selections.size()>0){
								for(ChatPojo c:selections){
									adapter.pojos.remove(c);

									// Own messages have no sender, so the chat's address must come
									// from the connected device, not from the message.
									String ds= device.getAddress();
									if(c.index > 0) app.chatStore.delete(ds,c.index);
									adapter.notifyDataSetChanged();

									if(c.uri != null){

										if(!(deleteFile(c.uri.getPath()))){
											Toast.makeText(ChatActivity.this,"Couldn't remove the attached file from your phone",Toast.LENGTH_SHORT).show();
										}
									}

									if(adapter.pojos.size() <= 0){
										app.chatBase.delete(ds);
										app.chatStore.clear(ds);
										ClassicCompat.clearUnreads(ChatActivity.this,device.getAddress());
									} else {
										updateLast();
									}
								}
							} else {
								Toast.makeText(ChatActivity.this,"Nothing selected",Toast.LENGTH_SHORT).show();
							}
							m.finish();
							return true;
						case "Cancel":
							m.finish();
							return true;
					}
					return false;
				}
			});
		} else {
			if(!cp.isSelected){
				selections.add(cp);
				cp.setSelected(true);
			} else {
				selections.remove(cp);
				cp.setSelected(false);
			}

			mode.setTitle(selections.size()+" selected");
		}
	}

	public void call(boolean video){
		Intent in= null;
		if(video){
			in= new Intent(this,VideoActivity.class);
		} else {
			in= new Intent(this,CallActivity.class);
		}
		in.putExtra("device",device);
		in.putExtra("type",CallActivity.TYPE_REQUEST);

		startActivity(in);
	}

	public void clear(){
		app.chatStore.clear(device.getAddress());
		adapter.pojos.clear();
		adapter.notifyDataSetChanged();
		app.chatBase.delete(device.getAddress());
	}
	public static String getSent(EcclesMessage m,Context c){
		String p= Reader.getPreview(m,c);
		return p == null ? null : "You: "+p;
	}

	public void updateLast(){
		if(adapter.pojos.isEmpty()) return;
		ChatPojo pj= adapter.pojos.get(adapter.pojos.size()-1);
		String lm= Reader.getPreview(pj.messenger,this);
		if(lm != null){
			// Use the message's own date so deleting a message doesn't make the older one look
			// like it just arrived.
			app.chatBase.updateNewMessage(device.getAddress(),pj.self ? "You: "+lm : lm,pj.messenger.date);
		}
	}

	public boolean deleteFile(String path){
		try{
		File file = new File(path);

		if(!file.delete()){
			if(!file.getCanonicalFile().delete()){

				if(getApplicationContext().deleteFile(file.getName())){

					return true;
				} else {
					return false;
				}
			} else {
				return true;
			}
		} else {
			return true;
		}

		} catch (Exception e){
			return false;
		}
	}
}