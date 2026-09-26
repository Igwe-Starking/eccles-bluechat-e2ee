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
import android.widget.VideoView;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;

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
				break;
			case VIEW_VIDEO:
				viewVideo(getIntent().getData());
				break;
			case VIEW_PROFILE:
				viewProfile(getIntent().getStringExtra("address"));
		}
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