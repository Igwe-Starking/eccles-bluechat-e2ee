package starking.eccles.bluechat.ui;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.transition.Slide;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;

public class ProfileDialog extends DialogFragment {

	private EcclesPojo pj;
	private AlertDialog al;

	private static final String ARG_TAG= "tag";

	/**
	 * DialogFragment subclasses must have a public no-arg constructor: if the system needs to
	 * recreate this fragment (a screen rotation, or process death + restoration, while a
	 * device's profile popup was open), it does so via reflection using exactly this
	 * constructor. Storing the whole {@link EcclesPojo} as a plain instance field on the 1-arg
	 * constructor below wouldn't survive that, since Bundles can't carry it directly. Unlike
	 * {@link EcclesDialog}'s Runnable callbacks, an EcclesPojo backing this dialog can be fully
	 * reconstructed on recreation from just its device address (itself Bundle-safe, unlike the
	 * pojo object) via {@link EcclesPojo}'s real constructor - see {@link #onCreateDialog} -
	 * so this restores full functionality after recreation, not just crash-prevention.
	 */
	public ProfileDialog(){
	}

	public ProfileDialog(EcclesPojo p){
		this.pj= p;
		Bundle args= new Bundle();
		args.putString(ARG_TAG,p.tag);
		setArguments(args);
	}

	@Override
	public Dialog onCreateDialog(Bundle bu){

		if(pj == null && getArguments() != null){
			String tag= getArguments().getString(ARG_TAG);
			EcclesApplication ea= tag != null ? (EcclesApplication) getActivity().getApplication() : null;
			BluetoothDevice d= ea != null && ea.adapter != null ? ea.adapter.getRemoteDevice(tag) : null;
			if(d != null){
				pj= new EcclesPojo(d,getActivity(),R.layout.pojo_view,null,false,null,0);
			}
		}
		if(pj == null){
			// Couldn't recover (e.g. adapter unavailable) - show something rather than NPE.
			return new AlertDialog.Builder(getActivity()).setMessage("Unable to load profile").create();
		}

		Slide slide= new Slide();
		slide.setDuration(1000);
		setEnterTransition(slide);

		View v= getActivity().getLayoutInflater().inflate(R.layout.profile_view,null);

		((TextView) v.findViewById(R.id.pro_t)).setText(pj.Title);
		((TextView) v.findViewById(R.id.pro_add)).setText(pj.tag);

		if(ClassicCompat.queryDeviceIcon(pj.tag,getActivity()) != null){
			BitmapDrawable bd= new BitmapDrawable(ClassicCompat.queryDeviceIcon(pj.tag,getActivity()));
			v.setBackground(bd);
			v.setOnClickListener((View vp)->{
				ClassicCompat.view((EcclesActivity)getActivity(),pj.tag);
			});
		}

		EcclesApplication ea= (EcclesApplication) getActivity().getApplication();
		if(!ea.isConnected(pj.tag)){
			v.findViewById(R.id.active_bt).setVisibility(View.GONE);
			Button b= v.findViewById(R.id.connect_bt);
			b.setOnClickListener((View vv)->{
				BluetoothDevice d= ea.adapter.getRemoteDevice(pj.tag);
				if(!ClassicCompat.queryDeviceType(d)){
					ea.currentActivity.showDialog("Bluechat does not recognize this device as an android device\nYou can still try to connect to this device if you insist","Connect","Cancel",null,()->{
						ea.currentActivity.connect(d,true);
					},null);
				} else {
					ea.currentActivity.connect(d,true);
				}
				al.cancel();
				al.dismiss();
			});
		} else {
			v.findViewById(R.id.connect_bt).setVisibility(View.GONE);
			v.findViewById(R.id.pro_chat).setOnClickListener((View vv)->{
				pj.launch(1);
				al.cancel();
				al.dismiss();
			});
			v.findViewById(R.id.pro_call).setOnClickListener((View vv)->{
				pj.launch(2);
				al.cancel();
				al.dismiss();
			});
			v.findViewById(R.id.pro_vid).setOnClickListener((View vv)->{
				pj.launch(3);
				al.cancel();
				al.dismiss();
			});
		}
	    return al= new AlertDialog.Builder(getActivity()).setView(v).setCancelable(true).create();
	}

	public void post(){

		EcclesApplication app= (EcclesApplication) pj.con.getApplicationContext();

		show(app.currentActivity.getSupportFragmentManager(),pj.tag);
	}
}