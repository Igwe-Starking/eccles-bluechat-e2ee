package starking.eccles.data;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import starking.eccles.Interface.EcclesMessage;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.activities.ChatActivity;
import starking.eccles.crypto.KeystoreAes;
import starking.eccles.util.FriendlyDate;

public class EcclesStorage {

	private static final KeystoreAes MEDIA_KEY= new KeystoreAes("eccles_media_key");

	public static String getDir(byte[] d,boolean self,Context c,int st){
		if(d==null || c==null) return null;
		String t; String ext;
		switch(st){
			case EcclesMessage.SUBTYPE_AUDIO: t="Audio"; ext=".m4a"; break;
			case EcclesMessage.SUBTYPE_IMAGE: t="Images"; ext=".jpg"; break;
			case EcclesMessage.SUBTYPE_VIDEO: t="Videos"; ext=".mp4"; break;
			default: return null;
		}
		try{
			File base=c.getExternalFilesDir("Eccles");
			if(base==null) base=c.getFilesDir();
			File dir=new File(base,"Chats/"+t);
			if(!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("unable to create media directory");
			File path=new File(dir,createRandomFileName()+ext);
			byte[] encrypted= MEDIA_KEY.encrypt(d);
			try(FileOutputStream fos=new FileOutputStream(path)){ fos.write(encrypted); fos.flush(); }
			return path.getAbsolutePath();
		}catch(Exception e){
			Log.e("EcclesStorage","getDir failed",e);
			return null;
		}
	}

	public static Uri resolveUri(Context c,String encryptedPath){
		if(c==null || encryptedPath==null) return null;
		try{
			File src= new File(encryptedPath);
			if(!src.exists()) return null;
			File cacheDir= new File(c.getCacheDir(),"eccles_media_plain");
			if(!cacheDir.exists()) cacheDir.mkdirs();
			String name= sha256Hex(encryptedPath)+extensionOf(encryptedPath);
			File out= new File(cacheDir,name);
			if(!out.exists() || out.length()==0){
				byte[] encrypted;
				try(FileInputStream fis= new FileInputStream(src)){
					encrypted= readAllBytes(fis);
				}
				byte[] plain= MEDIA_KEY.decrypt(encrypted);
				try(FileOutputStream fos= new FileOutputStream(out)){ fos.write(plain); fos.flush(); }
			}
			return Uri.fromFile(out);
		}catch(Exception e){
			Log.e("EcclesStorage","resolveUri failed to decrypt media",e);
			return null;
		}
	}

	private static String extensionOf(String path){
		int i= path.lastIndexOf('.');
		return i>=0 ? path.substring(i) : "";
	}

	private static String sha256Hex(String s) throws Exception {
		MessageDigest md= MessageDigest.getInstance("SHA-256");
		byte[] d= md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		StringBuilder sb= new StringBuilder();
		for(byte b:d) sb.append(String.format("%02x",b));
		return sb.toString();
	}

	public static void clearPlaintextCache(Context c){
		try{
			File cacheDir= new File(c.getCacheDir(),"eccles_media_plain");
			File[] files= cacheDir.listFiles();
			if(files!=null) for(File f:files) f.delete();
		}catch(Exception e){ Log.e("EcclesStorage","clearPlaintextCache failed",e); }
	}

	public static boolean isAllowed(EcclesActivity a){ return true; }

	public static String createRandomFileName(){
		return "Eccles_"+java.util.UUID.randomUUID().toString().replace("-","");
	}

	public static long extractDuration(Context c,Uri u){
		MediaMetadataRetriever r= new MediaMetadataRetriever();
		try{
			// setDataSource() throws for a malformed/corrupt/unsupported media file, or a URI
			// that's no longer readable - previously this call was outside any try/catch, so
			// any of those (a genuinely reachable case for received media, not just
			// hypothetical) would throw uncaught all the way up through ChatAdapter's row
			// binding, which calls this for every audio/video message row as it's bound - a
			// single bad file could crash the app just from scrolling chat history.
			r.setDataSource(c,u);
		} catch (Exception e){
			Log.e("EcclesStorage","extractDuration: setDataSource failed",e);
			try{ r.release(); }catch(Exception ignored){}
			return 0L;
		}
		String d= r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
		try {
			r.release();
		}catch(Exception e){ Log.e("EcclesStorage","release failed",e); }
		try{
			return Long.parseLong(d);
		}catch(Exception e){
			return 0L;
		}
	}

	private static byte[] readAllBytes(InputStream stream) throws java.io.IOException {
		ByteArrayOutputStream out= new ByteArrayOutputStream();
		byte[] buf= new byte[8192];
		int n;
		while((n= stream.read(buf)) != -1){
			out.write(buf,0,n);
		}
		return out.toByteArray();
	}

	public static byte[] getBytes(Uri u,Context c){
		try(InputStream stream= c.getContentResolver().openInputStream(u)){
			return readAllBytes(stream);
		} catch (Exception e){
			Log.e("Eccles","getBytes() failed",e);
			return null;
		}
	}

	public static byte[] getCompressedImageBytes(Uri u,Context c){
		final int MAX_DIMENSION= 1600;
		try{
			android.graphics.BitmapFactory.Options bounds= new android.graphics.BitmapFactory.Options();
			bounds.inJustDecodeBounds= true;
			try(InputStream probe= c.getContentResolver().openInputStream(u)){
				android.graphics.BitmapFactory.decodeStream(probe,null,bounds);
			}
			if(bounds.outWidth<=0 || bounds.outHeight<=0) return getBytes(u,c);

			int sample= 1;
			int longest= Math.max(bounds.outWidth,bounds.outHeight);
			while(longest/(sample*2)>=MAX_DIMENSION){
				sample*= 2;
			}

			android.graphics.BitmapFactory.Options opts= new android.graphics.BitmapFactory.Options();
			opts.inSampleSize= sample;
			Bitmap bm;
			try(InputStream in= c.getContentResolver().openInputStream(u)){
				bm= android.graphics.BitmapFactory.decodeStream(in,null,opts);
			}
			if(bm == null) return getBytes(u,c);

			ByteArrayOutputStream bos= new ByteArrayOutputStream();
			bm.compress(Bitmap.CompressFormat.JPEG,85,bos);
			bm.recycle();
			return bos.toByteArray();
		} catch (Exception e){
			Log.e("Eccles","getCompressedImageBytes() failed, falling back to raw bytes",e);
			return getBytes(u,c);
		}
	}

	public static Bitmap extractThumb(ChatActivity a,Uri u){
		MediaMetadataRetriever r= new MediaMetadataRetriever();
		try{
			// Same reasoning as extractDuration(): setDataSource()/getFrameAtTime() can throw
			// for a malformed/corrupt video file, and this is called for every video message
			// row as it's bound in ChatAdapter, not just on playback.
			r.setDataSource(a,u);
			Bitmap b= r.getFrameAtTime();
			try {
				r.release();
			}catch(Exception e){ Log.e("EcclesStorage","release failed",e); }
			return b;
		} catch (Exception e){
			Log.e("EcclesStorage","extractThumb failed",e);
			try{ r.release(); }catch(Exception ignored){}
			return null;
		}
	}

	public static Uri getTone(Context c){
		try{
			File f= new File(c.getExternalFilesDir("Eccles")+"/ringtone/tone.mp3");
			if(f.exists()){
				return Uri.fromFile(f);
			}
			throw new Exception();
		} catch (Exception e){
			return Settings.System.DEFAULT_RINGTONE_URI;
		}
	}

	public static boolean saveTone(Context c,Uri u){
		try{
		File f= new File(c.getExternalFilesDir("Eccles")+"/ringtone/");
		if(f.exists() || f.mkdirs()){
			File p= new File(f.getAbsolutePath()+"/tone.mp3");
			if(p.exists() || p.createNewFile()){
				byte[] b;
				try(InputStream is= c.getContentResolver().openInputStream(u)){
					b= readAllBytes(is);
				}
				try(FileOutputStream fs= new FileOutputStream(p)){
					fs.write(b);
					fs.flush();
				}
				return true;
				}
		}
		} catch (Exception e){
			Log.e("Eccles","error saving ringtone",e);
		}
		return false;
	}

	public static String getSize(Context c,File f){
		try{
			return Formatter.formatShortFileSize(c,f.length());
		} catch (Exception e){
			return null;
		}
	}

	public static long getFileSize(Context c,Uri u){
		try(Cursor cursor= c.getContentResolver().query(u,null,null,null,null)){
			if(cursor != null && cursor.moveToFirst()){
				int idx= cursor.getColumnIndex(OpenableColumns.SIZE);
				if(idx != -1 && !cursor.isNull(idx)){
					return cursor.getLong(idx);
				}
			}
		} catch (Exception e){
			Log.e("Eccles","getFileSize() query failed",e);
		}
		try(android.content.res.AssetFileDescriptor afd= c.getContentResolver().openAssetFileDescriptor(u,"r")){
			if(afd != null) return afd.getLength();
		} catch (Exception e){
			Log.e("Eccles","getFileSize() afd fallback failed",e);
		}
		return -1L;
	}

	public static String getDisplayName(Context c,Uri u){
		try(Cursor cursor= c.getContentResolver().query(u,null,null,null,null)){
			if(cursor != null && cursor.moveToFirst()){
				int idx= cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if(idx != -1){
					String name= cursor.getString(idx);
					if(name != null) return name;
				}
			}
		} catch (Exception e){
			Log.e("Eccles","getDisplayName() failed",e);
		}
		String last= u.getLastPathSegment();
		return last != null ? last : "file";
	}

	public static boolean verifySize(Context c,Uri u){
		long size= getFileSize(c,u);
		return size >= 0 && size < 3000000;
	}

}
