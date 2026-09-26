package starking.eccles.bluechat.activities;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.util.Log;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.view.MenuItemCompat;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.pref.AccountPref;
import starking.eccles.crypto.IdentityKeyManager;
import starking.eccles.data.EcclesStorage;
import starking.eccles.util.EcclesIcon;

public class AccountActivity extends EcclesOptions {

	private ImageView icon,select;
	private static final int SELECT_FILE= 21;
	private static final int SELECT_CAM= 24;

	@Override
	public void onCreate(Bundle bundle){
		super.onCreate(bundle);

		getSupportActionBar().setTitle("Profile");

		setContentView(R.layout.activity_account);
		getSupportFragmentManager()
		.beginTransaction()
		.replace(R.id.pref_con,new AccountPref())
		.commit();
	}

	@Override
	public void onStart(){

		icon= findViewById(R.id.icon);
		select= findViewById(R.id.select);

		updateImage();
		updateSecurityKey();
		super.onStart();

		if(Build.VERSION.SDK_INT>=30){
		final PopupWindow pw= new PopupWindow(this);
		pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		pw.setOutsideTouchable(true);

		View pv= getLayoutInflater().inflate(R.layout.account_select,null);
		pv.findViewById(R.id.open_camera).setOnClickListener((View v1)->{
			selectIcon(false);
			pw.dismiss();
		});
		pv.findViewById(R.id.from_file).setOnClickListener((View v2)->{
			selectIcon(true);
			pw.dismiss();
		});

		pw.setContentView(pv);

		select.setOnClickListener((View vv)->{
			pw.showAsDropDown(vv);
		});
		} else {
			final PopupMenu pm= new PopupMenu(this,select);
			pm.inflate(R.menu.icon_menu);
			pm.setOnMenuItemClickListener((MenuItem item)->{
				switch(item.getItemId()){
					case R.id.from_carm:
						selectIcon(false);
						break;
					case R.id.from_file:
						selectIcon(true);
				}
				return true;
			});
			select.setOnClickListener((View vv)->{
				pm.show();
			});
		}
	}

	public void updateSecurityKey(){
		TextView keyView= findViewById(R.id.security_key_value);
		if(keyView == null) return;
		try{
			IdentityKeyManager id= IdentityKeyManager.get(this);
			keyView.setText(id.getFingerprintHex());
			keyView.setOnClickListener((View v)->{
				Toast.makeText(this,"This is your device's security key. Compare it with a contact through another channel (in person, a phone call) to verify your Bluetooth conversation cannot be intercepted.",Toast.LENGTH_LONG).show();
			});
		} catch(Exception e){
			Log.e("Eccles","failed to load security key",e);
			keyView.setText("unavailable");
		}
	}

	public void updateImage(){
		Bitmap b= ClassicCompat.queryDeviceIcon("Eccles",this);
		if(b != null){
			icon.setImageDrawable(EcclesIcon.resize(100,100,b,this));
		} else {
			icon.setImageDrawable(EcclesIcon.resize(100,100,R.drawable.user,this));
		}

		icon.setOnClickListener((View v)->{
			ClassicCompat.view(this,"Eccles");
		});
	}

	private void selectIcon(boolean fromFile){
		Intent in= new Intent();
		if(fromFile){
			in.setType("image/*");
			in.setAction(Intent.ACTION_GET_CONTENT);
			startActivityForResult(in,SELECT_FILE);
		}else {
			if(requestPermission(new String[]{Manifest.permission.CAMERA},CAMERA_PERM)){
				in= new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
				startActivityForResult(in,SELECT_CAM);
			} else {
				return;
			}
		}

	}

	@Override
	public void onActivityResult(int rq,int res,Intent d){
		super.onActivityResult(rq,res,d);
		try{
		if(rq == SELECT_CAM && res == RESULT_OK){
			Bitmap b= (Bitmap)d.getExtras().get("data");
				ClassicCompat.saveDeviceIcon("Eccles",EcclesIcon.convertToBytes(b),this);
				ClassicCompat.clearRecipients(this);
		} else if(rq == SELECT_FILE && res == RESULT_OK){
			ClassicCompat.saveDeviceIcon("Eccles",EcclesStorage.getBytes(d.getData(),this),this);
			ClassicCompat.clearRecipients(this);
		}
		} catch (Exception e){
			Log.e("Eccles","image update failed",e);
			Toast.makeText(this,"Failed to update Image",1).show();
		}
	}

}