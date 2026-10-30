package starking.eccles.bluechat.activities;

import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.data.EcclesStorage;

public class ViewActivity extends EcclesOptions {

	public static final int VIEW_IMAGE= 2;
	public static final int VIEW_VIDEO= 4;
	public static final int VIEW_PROFILE= 6;

	@Override
	public void onCreate(Bundle b){

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

		super.onCreate(b);

		setContentView(R.layout.activity_view);

		if(getIntent().getExtras() == null){
			finish();
			return;
		}

		switch(getIntent().getExtras().getInt("type")){

			case VIEW_IMAGE:
				viewImage(getIntent().getData());
				setupDownload(getIntent().getData(),"jpg","image/jpeg");
				break;
			case VIEW_VIDEO:
				viewVideo(getIntent().getData());
				setupDownload(getIntent().getData(),"mp4","video/mp4");
				break;
			case VIEW_PROFILE:
				viewProfile(getIntent().getStringExtra("address"));
		}
	}

	/** Download only ever shows up here, not inline in ChatActivity's chat bubbles - and not at
	 *  all for VIEW_PROFILE, which this is simply never called for (there's nothing meaningful
	 *  to "download" about a contact's icon). */
	private void setupDownload(Uri source,String extension,String mimeType){
		FloatingActionButton download= findViewById(R.id.download);
		if(source == null || download == null) return;
		download.setVisibility(View.VISIBLE);
		download.setOnClickListener(v-> {
			String name= EcclesStorage.getDisplayName(this,source);
			if(!name.contains(".")) name= name+"."+extension;
			Uri saved= EcclesStorage.saveToDownloads(this,source,name,mimeType);
			Toast.makeText(this,saved != null ? "Saved to Downloads" : "Failed to save file",Toast.LENGTH_SHORT).show();
		});
	}

	private void viewImage(Uri u){
		ImageView im= findViewById(R.id.image);
		im.setImageURI(u);
		im.setVisibility(View.VISIBLE);
	}

	private void viewProfile(String add){

		Bitmap b= ClassicCompat.queryDeviceIcon(add,this);
		ImageView im= findViewById(R.id.image);
		im.setImageBitmap(b);
		im.setVisibility(View.VISIBLE);
		if(add.equals("Eccles")){
			registerForContextMenu(im);
		}
	}
	private void viewVideo(Uri u){
		VideoView vv= findViewById(R.id.video);
		vv.setVideoURI(u);
		vv.setMediaController(new MediaController(this));
		vv.setVisibility(View.VISIBLE);
		vv.setOnCompletionListener((MediaPlayer p)->{
			try{
				p.release();
			} catch(Exception e){ android.util.Log.e("ViewActivity", "Suppressed exception", e); }
			finish();
		});
		vv.start();
	}

	@Override
	public void onCreateContextMenu(ContextMenu m,View v,ContextMenu.ContextMenuInfo i){
		m.add("Delete");
	}

	@Override
	public boolean onContextItemSelected(MenuItem it){
		if(it.getTitle().equals("Delete")){
			ClassicCompat.deleteDeviceIcon("Eccles",this);
			((ImageView)findViewById(R.id.image)).setImageResource(R.drawable.user);
		}
		return false;
	}
}