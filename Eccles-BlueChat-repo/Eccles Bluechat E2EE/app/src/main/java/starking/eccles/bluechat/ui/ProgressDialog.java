package starking.eccles.bluechat.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import starking.eccles.bluechat.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ProgressDialog extends EcclesDialog {

	/**
	 * Constructors are not inherited in Java, so even with {@link EcclesDialog} having a
	 * no-arg constructor, this subclass still needs its own - Android's Fragment recreation
	 * reflection looks for a no-arg constructor on the exact runtime class, not a superclass.
	 * Without this, recreating a showing ProgressDialog (screen rotation, process death) would
	 * throw InstantiationException.
	 */
	public ProgressDialog(){
		super();
	}

	public ProgressDialog(String msg){
		super(msg,null,null,null,null,null);
	}

	@Override
	public Dialog onCreateDialog(Bundle bundle){
		// This overrides EcclesDialog's onCreateDialog entirely (doesn't call super), so the
		// parent's argument-restoration logic never runs here - restore 'message' directly
		// using the same key, or a system-triggered recreation would show a blank message
		// instead of a crash, but still not the intended text.
		if(message == null && getArguments() != null){
			message= getArguments().getString(ARG_MESSAGE);
		}
		MaterialAlertDialogBuilder b= new MaterialAlertDialogBuilder(getActivity(),R.style.AlertDialog_rounded);
		View v= LayoutInflater.from(getActivity()).inflate(R.layout.progress_dialog,null);
		((TextView)v.findViewById(R.id.protext)).setText(message);
		b.setView(v);
		return al= b.create();
	}
}