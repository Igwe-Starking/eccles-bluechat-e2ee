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

	/** Profile pictures are stored and sent as this size (px, square). Small on purpose: a
	 *  256x256 JPEG is typically 10-30 KB, so it crosses Bluetooth in a moment and never
	 *  delays chats or calls, however large the original photo was. */
	public static final int ICON_SIZE= 256;
	private static final int ICON_MAX_BYTES= 60_000;

	/**
	 * Turns ANY photo (a 12 MP camera picture, a huge gallery image) into a small square profile
	 * picture ready to store and send: decoded at reduced size (so a big photo never exhausts
	 * memory), rotated upright using its EXIF tag, centre-cropped to a square, scaled to
	 * {@link #ICON_SIZE} and JPEG-compressed. Returns null only if the file isn't a readable image.
	 */
	public static byte[] prepareIcon(Context c,android.net.Uri u){
		try{
			BitmapFactory.Options bounds= new BitmapFactory.Options();
			bounds.inJustDecodeBounds= true;
			try(java.io.InputStream in= c.getContentResolver().openInputStream(u)){
				BitmapFactory.decodeStream(in,null,bounds);
			}
			if(bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

			int sample= 1;
			int shortest= Math.min(bounds.outWidth,bounds.outHeight);
			while(shortest/(sample*2) >= ICON_SIZE*2) sample*= 2;

			BitmapFactory.Options opts= new BitmapFactory.Options();
			opts.inSampleSize= sample;
			Bitmap decoded;
			try(java.io.InputStream in= c.getContentResolver().openInputStream(u)){
				decoded= BitmapFactory.decodeStream(in,null,opts);
			}
			if(decoded == null) return null;

			int degrees= 0;
			if(android.os.Build.VERSION.SDK_INT >= 24){
				try(java.io.InputStream in= c.getContentResolver().openInputStream(u)){
					int o= new android.media.ExifInterface(in).getAttributeInt(
							android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL);
					if(o == android.media.ExifInterface.ORIENTATION_ROTATE_90) degrees= 90;
					else if(o == android.media.ExifInterface.ORIENTATION_ROTATE_180) degrees= 180;
					else if(o == android.media.ExifInterface.ORIENTATION_ROTATE_270) degrees= 270;
				} catch(Exception ignored){}
			}
			if(degrees != 0){
				android.graphics.Matrix m= new android.graphics.Matrix();
				m.postRotate(degrees);
				Bitmap rotated= Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),m,true);
				if(rotated != decoded) decoded.recycle();
				decoded= rotated;
			}
			return prepareIcon(decoded);
		} catch(Throwable t){
			android.util.Log.e("EcclesIcon","prepareIcon failed",t);
			return null;
		}
	}

	/** Same as {@link #prepareIcon(Context, android.net.Uri)} for an already-decoded bitmap. */
	public static byte[] prepareIcon(Bitmap src){
		if(src == null) return null;
		try{
			int side= Math.min(src.getWidth(),src.getHeight());
			Bitmap square= Bitmap.createBitmap(src,(src.getWidth()-side)/2,(src.getHeight()-side)/2,side,side);
			Bitmap scaled= Bitmap.createScaledBitmap(square,ICON_SIZE,ICON_SIZE,true);
			if(scaled.hasAlpha()){
				// JPEG has no transparency - put the picture on white instead of black.
				Bitmap flat= Bitmap.createBitmap(ICON_SIZE,ICON_SIZE,Bitmap.Config.ARGB_8888);
				android.graphics.Canvas cv= new android.graphics.Canvas(flat);
				cv.drawColor(android.graphics.Color.WHITE);
				cv.drawBitmap(scaled,0,0,null);
				scaled= flat;
			}
			byte[] out= null;
			for(int q: new int[]{85,70,55,40}){
				ByteArrayOutputStream bos= new ByteArrayOutputStream();
				scaled.compress(Bitmap.CompressFormat.JPEG,q,bos);
				out= bos.toByteArray();
				if(out.length <= ICON_MAX_BYTES) break;
			}
			return out;
		} catch(Throwable t){
			android.util.Log.e("EcclesIcon","prepareIcon failed",t);
			return null;
		}
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