package starking.eccles.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import starking.eccles.bluechat.R;

public class EcclesIcon {

	public static RoundedBitmapDrawable resize(int h,int w,int r,Context con){

		Bitmap b= ((BitmapDrawable) con.getResources().getDrawable(r)).getBitmap();
		Bitmap re= Bitmap.createScaledBitmap(b,w,h,false);
		RoundedBitmapDrawable d= RoundedBitmapDrawableFactory.create(con.getResources(),re);
		d.setCircular(true);
		return d;
	}

	public static RoundedBitmapDrawable resize(int h,int w,Bitmap b,Context con){
		if(b == null) b= ((BitmapDrawable)con.getResources().getDrawable(R.drawable.user)).getBitmap();
		Bitmap re= Bitmap.createScaledBitmap(b,w,h,false);
		RoundedBitmapDrawable d= RoundedBitmapDrawableFactory.create(con.getResources(),re);
		d.setCircular(true);
		return d;
	}

	public static byte[] convertToBytes(Bitmap b){

		if(b != null){
		ByteArrayOutputStream bis= new ByteArrayOutputStream();
		b.compress(Bitmap.CompressFormat.PNG,50,(OutputStream)bis);
		return bis.toByteArray();
		}
		return null;
	}

	/**
	 * Maximum decoded pixel dimensions/area allowed for {@link #convertToBitmap}. Profile
	 * icons are always immediately downscaled to a small on-screen size via {@link #resize},
	 * so there is no legitimate reason to ever need more resolution than this.
	 */
	private static final int MAX_ICON_DIMENSION= 1024;
	private static final long MAX_ICON_PIXELS= (long) MAX_ICON_DIMENSION * MAX_ICON_DIMENSION;

	public static Bitmap convertToBitmap(byte[] d){
		if(d == null) return null;
		try{
			// Decode only the declared dimensions first, without allocating any pixel memory -
			// this data can come from a remote peer's profile icon (an authenticated peer, but
			// "authenticated" does not mean "not malicious or buggy"). Without this check, a
			// small compressed payload whose declared dimensions are enormous ("decompression
			// bomb") would force a correspondingly enormous in-memory Bitmap allocation on the
			// real decode below, a real OOM/DoS surface.
			BitmapFactory.Options bounds= new BitmapFactory.Options();
			bounds.inJustDecodeBounds= true;
			BitmapFactory.decodeByteArray(d,0,d.length,bounds);

			if(bounds.outWidth <= 0 || bounds.outHeight <= 0){
				return null;
			}
			if((long) bounds.outWidth * bounds.outHeight > MAX_ICON_PIXELS){
				android.util.Log.w("EcclesIcon","rejected oversized icon: "+bounds.outWidth+"x"+bounds.outHeight);
				return null;
			}

			BitmapFactory.Options opts= new BitmapFactory.Options();
			opts.inSampleSize= sampleSizeFor(bounds.outWidth,bounds.outHeight);
			return BitmapFactory.decodeByteArray(d,0,d.length,opts);
		} catch(Exception e){
			android.util.Log.e("EcclesIcon","convertToBitmap failed",e);
			return null;
		}
	}

	private static int sampleSizeFor(int width,int height){
		int sampleSize= 1;
		while(width/sampleSize > MAX_ICON_DIMENSION || height/sampleSize > MAX_ICON_DIMENSION){
			sampleSize*= 2;
		}
		return sampleSize;
	}
}