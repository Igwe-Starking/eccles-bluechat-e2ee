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
			Toast.makeText(this,"invalid bluetooth device",1).show();
			finish();
			return;
		}

		Toolbar toolbar= findViewById(R.id.chat_tools);
		setSupportActionBar(toolbar);
		getSupportActionBar().setTitle(device.getName());
		getSupportActionBar().setIcon(EcclesIcon.resize(60,60,ClassicCompat.queryDeviceIcon(device.getAddress(),this),this));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		pic= findViewById(R.id.picture);
		file= findViewById(R.id.carm);
		send= findViewById(R.id.send_bt);
		cardView= findViewById(R.id.input_parent);
		input= findViewById(R.id.input);
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

		chatList.setAdapter(adapter);
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

		send.setOnTouchListener((View v,MotionEvent mt)->{

			if (mt.getAction() == MotionEvent.ACTION_DOWN){
					if(input.getText() == null || input.getText().length() <= 0){
						recorder = recordVoice("Eccles123");
					}
			}
			return super.onTouchEvent(mt);
		});

		send.setOnClickListener((View view)->{
			if(recorder != null){
				File u= getVoice(recorder);
				byte[] b= null;
				try{
					b= Files.readAllBytes(u.toPath());
					sendMessage(EcclesMessage.SUBTYPE_AUDIO,b);
				} catch (Exception e){
					Log.e("Eccles","invalid file",e);
					Toast.makeText(this,"failed to send audio "+"\n"+e,1).show();
				}

				recorder= null;
			} else {
				sendMessage(EcclesMessage.SUBTYPE_TEXT,input.getText().toString().getBytes(StandardCharsets.UTF_8));
				input.setText(null);
			}
		});

		if("offline".equals(sbt)){

			startIntermittent();
			toggleInputButton(false);
		} else {
			activateButton();
			buttonActivated= true;
		}

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
		m.add("call").setIcon(R.drawable.ic_call).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		m.add("video").setIcon(R.drawable.ic_video_call).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		m.add(app.isConnected(device.getAddress()) ?"Disconnect" : "Connect");
		m.add(block(device.getAddress(),BLOCK_CHECK_BLOCK)? "unBlock":"Block");
		m.add("Clear Chat");
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId() == android.R.id.home){
			super.onBackPressed();
			return false;
		}

		switch (item.getTitle().toString()){
			case "call":
				call(false);
				return false;
			case "video":
				call(true);
				return false;
			case "Disconnect":
				disconnect(device,true);
				return false;
			case "Block":
				block(device.getAddress(),BLOCK_BLOCK);
				return false;
			case "Clear Chat":
				clear();
				return false;
			case "unBlock":
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
				if(!app.isConnected(device.getAddress())){
					runOnUiThread(()->{
						getSupportActionBar().setSubtitle("connecting");
						connect(device,false);
					});
				}
			},10,20,TimeUnit.SECONDS);
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
		super.onStop();
	}

	@Override
	public void onConnectionFailed(BluetoothDevice device,String r){
		runOnUiThread(()->{
		if(this.device== device){
			updateStatus("offline");
		}
		});
	}

	@Override
	public void onConnected(BluetoothSocket socket){
		runOnUiThread(()->{
		if(socket.getRemoteDevice() == this.device){
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
		int ic= sen ?android.R.drawable.ic_menu_send : android.R.drawable.presence_audio_away;
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

	private void openCarm(String w,int rc){
		Intent in= new Intent(w);
		ActivityCompat.startActivityForResult(this,in,rc,null);
	}

	private void selectStuff(String s,int rc){
		Intent in= new Intent();
		in.setType(s);
		in.setAction(Intent.ACTION_GET_CONTENT);
		ActivityCompat.startActivityForResult(this,in,rc,null);
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
				Toast.makeText(this,"Failed to save media, message not sent",Toast.LENGTH_SHORT).show();
				return;
			}
			m.setData(storedPath.getBytes());
		} else {
			m.setData(message.data);
		}
		m.setDate(FriendlyDate.format());
		try{
			if(!app.chatStore.insert(ClassicCompat.purifyAddress(device.getAddress()),"",m)){
				// insert() now correctly fails (returns false) rather than silently storing an
				// empty row when at-rest encryption fails - see ChatBase#insert. The message
				// still gets sent over the wire regardless (that's a separate, already-in-flight
				// operation), but it won't survive an app restart if this failed, so at least
				// log it for diagnosability rather than silently losing local history.
				Log.w("Eccles","failed to persist locally-sent message for "+device.getName());
			}
		} catch (Exception e){ android.util.Log.e("ChatActivity", "Suppressed exception", e); }
		if(!app.chatBase.insert(device,getSent(m,this))){
			if(!app.chatBase.updateNewMessage(device.getAddress(),getSent(m,this))){
				Log.w("Eccles","failed to update message sent to "+device.getName());
			}
		}

		ChatPojo p= new ChatPojo(m,this);
		p.setStatus("sending");
		updateAdapter(p);
		}
	}
	@Override
	public void sent(BluetoothSocket s,EcclesMessage m){
		ChatPojo p= adapter.findPojoWithTag(m.sender);
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
		ChatPojo p= adapter.findPojoWithTag(m.sender);
		if(p != null){
			p.setStatus("failed");
			adapter.notifyDataSetChanged();
			app.chatStore.updateStatus(device.getAddress(),"failed",p.index);
		}
		Log.w("Eccles","failed to send message to "+s.getRemoteDevice().getName()+"\n"+r);
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
			Toast.makeText(this,"the selected file does not exist",1).show();
			return;
		}
		if(u== null) return;
		if(!EcclesStorage.verifySize(this,u) && !app.pref.getBoolean("send_large",false)){
			String n= EcclesStorage.getDisplayName(this,u);
			long sizeBytes= EcclesStorage.getFileSize(this,u);
			String s= sizeBytes>=0 ? Formatter.formatShortFileSize(this,sizeBytes) : "unknown";
			SpannableStringBuilder b= new SpannableStringBuilder()
					.append("File Larger than expected\n",new ForegroundColorSpan(Color.RED),0)
					.append("Files Larger than 3MB is not recommended to be sent using this platform,this is because bluetooth is slow in communication and large files may delay you from chatting and calling devices until they are sent\nif the receiver goes out of range during the receiving process the file will never be received at all\nif you intend to send large files use file sharing app instead")
					.append("File:"+n+"\n"+"Size:"+s,new ForegroundColorSpan(Color.WHITE),0);

			final Uri u1= u;
			showDialog(b.toString(),"Send","Forget","Always Send",()->{
				if(progressDialog.checked){
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
			Log.e("Eccles","file send failed",e);
		} catch (OutOfMemoryError oe){
			Toast.makeText(this,"File too large cant send",1).show();
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

	public void performDisconnect(BluetoothDevice d){
		if(this.device==d) toggleInputButton(false);
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

					m.setTitle(selections.size()+" Chats Selected");

					MenuItem it1= mn.add("delete");
					it1.setIcon(android.R.drawable.ic_menu_delete);
					it1.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

					MenuItem it2= mn.add("cancel");
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
						case "delete":
							if(selections.size()>0){
								for(ChatPojo c:selections){
									adapter.pojos.remove(c);

									String ds= c.messenger.sender;
									app.chatStore.delete(ds,c.index);
									adapter.notifyDataSetChanged();

									if(c.uri != null){

										if(!(deleteFile(c.uri.getPath()))){
											Toast.makeText(ChatActivity.this,"Failed to delete file located at "+c.uri.toString(),1).show();
										}else {
											Toast.makeText(ChatActivity.this,"Deleted File at "+"\n"+c.uri.getPath(),1).show();
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
								Toast.makeText(ChatActivity.this,"No chat selected",1).show();
							}
							m.finish();
							return true;
						case "cancel":
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

			mode.setTitle(selections.size()+" Chats Selected");
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

		switch (m.subtype){
			case EcclesMessage.SUBTYPE_TEXT:
				return "you:"+new String(m.data);
			case EcclesMessage.SUBTYPE_AUDIO:
				return "you: sent an Audio ";
			case EcclesMessage.SUBTYPE_IMAGE:
				return "you: sent an image ";
			case EcclesMessage.SUBTYPE_VIDEO:
				return "you: sent a video ";
			default:
				return null;
		}
	}

	public void updateLast(){
		ChatPojo pj= adapter.pojos.get(adapter.pojos.size()-1);
		String lm= pj.messenger.subtype==EcclesMessage.SUBTYPE_TEXT?new String(pj.messenger.data):Reader.getMessage(pj.messenger,this);
		if(lm != null){
			app.chatBase.updateNewMessage(device.getAddress(),pj.self ? "you: "+lm:lm);
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