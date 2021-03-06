package com.iknow.android.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.iknow.android.interfaces.TrimVideoListener;
import com.iknow.android.models.VideoInfo;
import iknow.android.utils.DeviceUtil;
import iknow.android.utils.UnitConverter;
import iknow.android.utils.callback.SingleCallback;
import iknow.android.utils.thread.BackgroundExecutor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class TrimVideoUtil {

  public static boolean isDebugMode = false;
  public static final long MIN_SHOOT_DURATION = 3000L;// 最小剪辑时间3s
  public static final long MAX_SHOOT_DURATION = 10 * 1000L;//视频最多剪切多长时间10s
  public static final int MAX_COUNT_RANGE = 10;  //seekBar的区域内一共有多少张图片
  private static final int SCREEN_WIDTH_FULL = DeviceUtil.getDeviceWidth();
  public static final int RECYCLER_VIEW_PADDING = UnitConverter.dpToPx(35);
  public static final int VIDEO_FRAMES_WIDTH = SCREEN_WIDTH_FULL - RECYCLER_VIEW_PADDING * 2;

  public static final int VIDEO_MAX_DURATION = 10;// 10秒
  private static final int THUMB_WIDTH = (DeviceUtil.getDeviceWidth() - RECYCLER_VIEW_PADDING * 2) / VIDEO_MAX_DURATION;

  public static void trim(Context context, String inputFile, String outputFile, long startMs, long endMs, final TrimVideoListener callback) {
    final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    final String outputName = "trimmedVideo_" + timeStamp + ".mp4";
    outputFile = outputFile + "/" + outputName;

    String start = convertSecondsToTime(startMs / 1000);
    String duration = convertSecondsToTime((endMs - startMs) / 1000);

    /** 裁剪视频ffmpeg指令说明：
     * ffmpeg -ss START -t DURATION -i INPUT -vcodec copy -acodec copy OUTPUT
     -ss 开始时间，如： 00:00:20，表示从20秒开始；
     -t 时长，如： 00:00:10，表示截取10秒长的视频；
     -i 输入，后面是空格，紧跟着就是输入视频文件；
     -vcodec copy 和 -acodec copy 表示所要使用的视频和音频的编码格式，这里指定为copy表示原样拷贝；
     INPUT，输入视频文件；
     OUTPUT，输出视频文件
     */
    String cmd = "-ss " + start + " -t " + duration + " -i " + inputFile + " -vcodec copy -acodec copy " + outputFile;
    String[] command = cmd.split(" ");
    try {
      final String tempOutFile = outputFile;
      FFmpeg.getInstance(context).execute(command, new ExecuteBinaryResponseHandler() {
        @Override public void onFailure(String s) {
        }

        @Override public void onSuccess(String s) {
          callback.onFinishTrim(tempOutFile);
        }

        @Override public void onStart() {
          callback.onStartTrim();
        }

        @Override public void onFinish() {
        }
      });
    } catch (FFmpegCommandAlreadyRunningException e) {
      e.printStackTrace();
    }
  }

  public static void backgroundShootVideoThumb(final Context context, final Uri videoUri, final int totalThumbsCount, final long startPosition,
      final long endPosition, final SingleCallback<ArrayList<Bitmap>, Integer> callback) {
    final ArrayList<Bitmap> thumbnailList = new ArrayList<>();
    BackgroundExecutor.execute(new BackgroundExecutor.Task("", 0L, "") {
      @Override public void execute() {
        try {
          MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
          mediaMetadataRetriever.setDataSource(context, videoUri);
          // Retrieve media data use microsecond
          long interval = (endPosition - startPosition) / (totalThumbsCount - 1);
          //每次截取到2帧之后上报
          for (long i = 0; i < totalThumbsCount; ++i) {
            long frameTime = startPosition + interval * i;
            Bitmap bitmap = mediaMetadataRetriever.getFrameAtTime(frameTime * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            try {
              bitmap = Bitmap.createScaledBitmap(bitmap, (int) (THUMB_WIDTH * 1.0f / bitmap.getWidth()), bitmap.getHeight(), false);
            } catch (Exception e) {
              e.printStackTrace();
            }
            thumbnailList.add(bitmap);
            if (thumbnailList.size() == 2) {
              callback.onSingleCallback((ArrayList<Bitmap>) thumbnailList.clone(), (int) interval);
              thumbnailList.clear();
            }
          }
          if (thumbnailList.size() > 0) {
            callback.onSingleCallback((ArrayList<Bitmap>) thumbnailList.clone(), (int) interval);
            thumbnailList.clear();
          }
          mediaMetadataRetriever.release();
        } catch (final Throwable e) {
          Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), e);
        }
      }
    });
  }

  /**
   * 需要设计成异步的
   */
  public static ArrayList<VideoInfo> getAllVideoFiles(Context mContext) {
    VideoInfo video;
    ArrayList<VideoInfo> videos = new ArrayList<>();
    ContentResolver contentResolver = mContext.getContentResolver();
    try {
      Cursor cursor =
          contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, null, MediaStore.Video.Media.DATE_MODIFIED + " desc");
      if (cursor != null) {
        while (cursor.moveToNext()) {
          video = new VideoInfo();
          if (cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION)) != 0) {
            video.setDuration(cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION)));
            video.setVideoPath(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATA)));
            video.setCreateTime(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)));
            video.setVideoName(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)));
            videos.add(video);
          }
        }
        cursor.close();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return videos;
  }

  public static String getVideoFilePath(String url) {
    if (TextUtils.isEmpty(url) || url.length() < 5) return "";
    if (url.substring(0, 4).equalsIgnoreCase("http")) {

    } else {
      url = "file://" + url;
    }

    return url;
  }

  private static String convertSecondsToTime(long seconds) {
    String timeStr = null;
    int hour = 0;
    int minute = 0;
    int second = 0;
    if (seconds <= 0)
      return "00:00";
    else {
      minute = (int) seconds / 60;
      if (minute < 60) {
        second = (int) seconds % 60;
        timeStr = "00:" + unitFormat(minute) + ":" + unitFormat(second);
      } else {
        hour = minute / 60;
        if (hour > 99)
          return "99:59:59";
        minute = minute % 60;
        second = (int) (seconds - hour * 3600 - minute * 60);
        timeStr = unitFormat(hour) + ":" + unitFormat(minute) + ":" + unitFormat(second);
      }
    }
    return timeStr;
  }

  private static String unitFormat(int i) {
    String retStr = null;
    if (i >= 0 && i < 10)
      retStr = "0" + Integer.toString(i);
    else
      retStr = "" + i;
    return retStr;
  }
}
