package starking.eccles.bluechat.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import starking.eccles.bluechat.R;

public class EcclesDialog extends DialogFragment {

	protected AlertDialog al;
	protected String message,pb,nb;
	private Runnable na,pa;
	private String items;
	public boolean checked;

	protected static final String ARG_MESSAGE= "message";
	private static final String ARG_PB= "pb";
	private static final String ARG_NB= "nb";
	private static final String ARG_ITEMS= "items";

	/**
	 * DialogFragment subclasses must have a public no-arg constructor: if the system needs to
	 * recreate this fragment (a screen rotation, or process death + restoration, while any
	 * confirmation dialog was showing), it does so via reflection using exactly this
	 * constructor - there previously wasn't one, only the 6-arg constructor below storing
	 * everything as plain instance fields, which would throw InstantiationException and crash
	 * the app. Given how pervasively this class is used across the app for confirmations
	 * (disconnect, delete, permission prompts, etc.), this was likely one of the most
	 * frequently reachable crashes in the whole codebase - simply rotating the phone while any
	 * such dialog was open would trigger it.
	 * <p>
	 * The String fields (message/pb/nb/items) now survive recreation via setArguments(), since
	 * Strings are Bundle-safe. The Runnable callbacks (pa/na) cannot be: Runnables aren't
	 * reliably serializable, and a lambda capturing an Activity reference would risk leaking
	 * the destroyed Activity if it somehow were serialized. So after a system-triggered
	 * recreation, the dialog still displays correctly, but its buttons won't run the original
	 * action (see the null-guards below) - a much smaller degradation than a crash, and an
	 * inherent limitation of Runnable-based callbacks that would need a different mechanism
	 * (e.g. the Fragment Result API) to fully close.
	 */
	public EcclesDialog(){
	}

	public EcclesDialog(String message,String pb,String nb,String nub,Runnable pa,Runnable na){
		this.message= message;
		this.pb= pb;
		this.nb= nb;
		this.pa= pa;
		this.na= na;
		this.items= nub;

		Bundle args= new Bundle();
		args.putString(ARG_MESSAGE,message);
		args.putString(ARG_PB,pb);
		args.putString(ARG_NB,nb);
		args.putString(ARG_ITEMS,nub);
		setArguments(args);
	}

	@Override
	public Dialog onCreateDialog(Bundle bundle){
		// Restore from arguments if the fields are unset (i.e. this instance was recreated by
		// the system via the no-arg constructor, rather than freshly created by a caller).
		if(message == null && pb == null && nb == null && items == null && getArguments() != null){
			Bundle args= getArguments();
			message= args.getString(ARG_MESSAGE);
			pb= args.getString(ARG_PB);
			nb= args.getString(ARG_NB);
			items= args.getString(ARG_ITEMS);
		}

		MaterialAlertDialogBuilder b= new MaterialAlertDialogBuilder(getActivity(),R.style.AlertDialog_rounded);

		SpannableString spt= new SpannableString("Eccles");
		spt.setSpan(new StyleSpan(Typeface.BOLD),0,spt.length(),0);
		spt.setSpan(new ForegroundColorSpan(Color.WHITE),0,spt.length(),0);
		spt.setSpan(new RelativeSizeSpan(1.8f),0,spt.length(),0);
		b.setTitle(spt);

		if(message != null){
			SpannableString sp= new SpannableString(message);
			sp.setSpan(new ForegroundColorSpan(Color.YELLOW),0,message.length(),0);
			b.setMessage(sp);
		}

		if(pb != null){
			SpannableString spp= new SpannableString(pb);
			spp.setSpan(new RelativeSizeSpan(1.5f),0,spp.length(),0);
			spp.setSpan(new ForegroundColorSpan(Color.GREEN),0,spp.length(),0);
			b.setPositiveButton((CharSequence)spp,(DialogInterface d,int p)->{
				if(pa != null){
					pa.run();
					al.cancel();
				}
			});
		}

		if(items != null){

			SpannableString spnn= new SpannableString(items);
			spnn.setSpan(new ForegroundColorSpan(Color.WHITE),0,spnn.length(),0);
			spnn.setSpan(new RelativeSizeSpan(0.75f),0,spnn.length(),0);

			View dv= LayoutInflater.from(getActivity()).inflate(R.layout.dialog_box,null);
			CheckBox cb= dv.findViewById(R.id.d_check);
			cb.setText(spnn);

			cb.setOnCheckedChangeListener((CompoundButton bt,boolean isChecked)->{
				checked= bt.isChecked();
			});

			b.setView(dv);
		}

		if(nb != null){
			SpannableString spn= new SpannableString(nb);
			spn.setSpan(new RelativeSizeSpan(1.45f),0,nb.length(),0);
			spn.setSpan(new ForegroundColorSpan(Color.RED),0,spn.length(),0);
			b.setNegativeButton((CharSequence) spn,(DialogInterface d,int p)->{
				if(na != null){
					na.run();
					al.cancel();
					al.dismiss();
				}
			});
		}

	    return al= b.create();
	}

	public void cancel(){
		al.cancel();
		al.dismiss();
	}
}