package com.example.vivinia.imspeech;

import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileActivity extends AppCompatActivity {

    private TextView mTvLog, mTvPressToSay;
    private ExecutorService mExecutorService;
    private MediaRecorder mMediaRecorder;
    private File mAudioFile;
    private long mStartRecorderTime, mStopRecorderTime;
    private Handler mMainThreadHandler;
    //主线程和后台播放线程数据同步
    private volatile boolean mIsPlaying;
    private MediaPlayer mMediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file);
        mTvLog = (TextView) findViewById(R.id.mTvLog);
        mTvPressToSay = (TextView) findViewById(R.id.mTvPressToSay);
        //录音JNI函数不具备线程安全性，所以要用单线程
        mExecutorService = Executors.newSingleThreadExecutor();    //初始化单线程的后台线程池
        mMainThreadHandler = new Handler(Looper.getMainLooper());

        //按下说话，释放发送，所以我们不要OnClickListener
        mTvPressToSay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                //根据不同touch action，执行不同逻辑
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startRecord();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stopRecord();
                        break;
                    default:
                        break;
                }
                //处理了touch事件返回true
                return true;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //activity销毁时，停止后台任务，避免内存泄漏
        mExecutorService.shutdownNow();
        releaseRecorder();
        stopPlay();
    }

    /**
     * 开始录音
     */
    private void startRecord() {
        //改变UI状态
        mTvPressToSay.setText("正在说话...");

        //提交后台任务，执行录音逻辑
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                //释放之前录音的MediaRecorder
                releaseRecorder();
                //执行录音逻辑，如果失败提示用户
                if (!doStart()) {
                    recordFail();
                }
            }
        });
    }

    /**
     * 启动录音逻辑
     *
     * @return
     */
    private boolean doStart() {
        try {
            //创建MediaRecorder
            mMediaRecorder = new MediaRecorder();
            //创建录音文件
            mAudioFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/IMSpeechDemo/" + System.currentTimeMillis() + ".m4a");  //m4a是MP4格式的声音文件的后缀
            mAudioFile.getParentFile().mkdirs();
            mAudioFile.createNewFile();
            //配置MediaRecorder
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);   //从麦克风采集
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); //保存文件为MP4格式
            mMediaRecorder.setAudioSamplingRate(44100);  //所有安卓系统都支持的采样频率
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);  //通用的AAC编码格式
            mMediaRecorder.setAudioEncodingBitRate(96000);  //音质比较好的频率
            mMediaRecorder.setOutputFile(mAudioFile.getAbsolutePath());   //设置录音文件位置
            //开始录音
            mMediaRecorder.prepare();  //准备
            mMediaRecorder.start();

            //记录开始录音时间，用于统计时长
            mStartRecorderTime = System.currentTimeMillis();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            //捕获异常，避免闪退，返回false提醒用户失败
            return false;
        }
        //录音成功
        return true;
    }

    /**
     * 停止录音逻辑
     *
     * @return
     */
    private boolean doStop() {
        try {
            //停止录音
            mMediaRecorder.stop();
            //记录停止时间，统计时长
            mStopRecorderTime = System.currentTimeMillis();
            //只接受超过3秒录音
            final int second = (int) ((mStopRecorderTime - mStartRecorderTime) / 1000);
            if (second > 3) {
                //在主线程改UI，显示出来
                mMainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mTvLog.setText("\n录音成功" + second + "秒");
                    }
                });
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            //捕获异常，避免闪退，返回false提醒用户失败
            return false;
        }
        //停止成功
        return true;
    }

    /**
     * 录音错误处理
     */
    private void recordFail() {
        mAudioFile = null;
        //给用户toast提示失败，主要在主线程执行
        mMainThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FileActivity.this, "录音失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 释放MediaRecorder
     */
    private void releaseRecorder() {
        //检查MediaRecorder不为null
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    /**
     * 停止录音
     */
    private void stopRecord() {
        //改变UI状态
        mTvPressToSay.setText("按住说话");
        //提交后台任务，执行停止逻辑
        mExecutorService.submit(new Runnable() {
            @Override
            public void run() {
                //执行停止录音逻辑，失败就要提醒用户
                if (!doStop()) {
                    recordFail();
                }
                //释放MediaRecorder
                releaseRecorder();
            }
        });
    }

    public void btnPlay(View v) {
        //检查当前状态，防止重复播放
        if (mAudioFile != null && !mIsPlaying) {
            //设置当前播放状态
            mIsPlaying = true;
            //提交后台任务，开始播放
            mExecutorService.submit(new Runnable() {
                @Override
                public void run() {
                    doPlay(mAudioFile);
                }
            });
        }

    }

    /**
     * 实际播放的逻辑
     *
     * @param audioFile
     */
    private void doPlay(File audioFile) {
        //配置播放器MediaPlayer
        mMediaPlayer = new MediaPlayer();
        try {
            //设置声音文件
            mMediaPlayer.setDataSource(audioFile.getAbsolutePath());
            //设置监听回掉
            mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    //播放结束，释放播放器
                    stopPlay();
                }
            });
            mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
                    //提示用户
                    playFail();
                    //释放播放器
                    stopPlay();
                    //错误已经处理，返回true
                    return true;
                }
            });
            //配置音量，是否循环
            mMediaPlayer.setVolume(1,1);
            mMediaPlayer.setLooping(false);
            //准备，开始
            mMediaPlayer.prepare();
            mMediaPlayer.start();
        } catch (RuntimeException |IOException e) {
            //异常处理，防止闪退
            e.printStackTrace();
            //提醒用户
            playFail();
            //释放播放器
            stopPlay();
        }

    }


    /**
     * 停止播放逻辑
     */
    private void stopPlay() {
        //充值播放状态
        mIsPlaying = false;
        //释放播放器
        if(mMediaPlayer!=null){
            //充值监听器，防止内存泄漏
            mMediaPlayer.setOnCompletionListener(null);
            mMediaPlayer.setOnErrorListener(null);
            mMediaPlayer.stop();
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer=null;
        }
    }

    /**
     * 提醒用户，播放失败
     */
    private void playFail() {
        //在主线程toast提示
        mMainThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(FileActivity.this, "播放失败", Toast.LENGTH_SHORT).show();
            }
        });
    }
}

