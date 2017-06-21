package com.example.vivinia.imspeech;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreamActivity extends AppCompatActivity {

    private Button mBtnStart;
    private TextView mTvLog;

    //录音状态,volatile保证多线程内存同步，避免出问题
    private volatile boolean mIsRecording;
    private ExecutorService mExecutorService;
    private Handler mMainThreadHandler;
    private File mAudioFile;
    private FileOutputStream mFileOutputStream;
    private AudioRecord mAudioRecord;
    private long mStartRecorderTime,mStopRecorderTime;
    //buffer不能太大，避免OOM（内存耗尽）
    private static final int BUFFER_SIZE=2048;
    private byte[] mBuffer;
    //主线程和后台播放线程数据同步
    private volatile boolean mIsPlaying;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream);

        mBtnStart= (Button) findViewById(R.id.mBtnStart);
        mTvLog= (TextView) findViewById(R.id.mTvLog);

        mBuffer=new byte[BUFFER_SIZE];

        //录音JNI函数不具备线程安全性，所以要用单线程
        mExecutorService= Executors.newSingleThreadExecutor();    //初始化单线程的后台线程池
        mMainThreadHandler=new Handler(Looper.getMainLooper());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //activity销毁时，释放资源，避免内存泄漏
        mExecutorService.shutdownNow();
    }

    public void streamStart(View v){
        //根据当前状态，改变UI，执行开始/停止录音的逻辑
        if(mIsRecording){
            //改变UI状态
            mBtnStart.setText("开始");
            //改变录音状态
            mIsRecording=false;
        }else{
            mBtnStart.setText("停止");
            //改变录音状态
            mIsRecording=true;
            //提交后台任务，执行录音逻辑
            mExecutorService.submit(new Runnable() {
                @Override
                public void run() {
                    //执行开始录音逻辑，失败提示用户
                    if(!startRecord()){
                        recordFail();
                    }
                }
            });
        }
    }

    /**
     * 启动录音逻辑
     * @return
     */
    private boolean startRecord() {
        try {
            //创建录音文件
            mAudioFile=new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/IMSpeechDemo/"+System.currentTimeMillis()+".pcm");
            mAudioFile.getParentFile().mkdirs();
            mAudioFile.createNewFile();
            //创建文件输出流
            mFileOutputStream=new FileOutputStream(mAudioFile);
            //配置AnduioRecord
            int audioSource= MediaRecorder.AudioSource.MIC;  //从麦克风采集
            int sampleRate=44100;  //所有安卓系统都支持的采样频率
            int channelConfig= AudioFormat.CHANNEL_IN_MONO;  //单声道输入
            int audioFormat=AudioFormat.ENCODING_PCM_16BIT;  //PCM16是所有安卓系统都支持的一个格式
            int minBufferSize=AudioRecord.getMinBufferSize(sampleRate,channelConfig,audioFormat);  //计算AudioRecord内部buffer的最小的大小
            mAudioRecord=new AudioRecord(audioSource,sampleRate,channelConfig,audioFormat,Math.max(minBufferSize,BUFFER_SIZE));  //buffer不能小于最低要求，也不能小于我们每次读取的大小

            //开始录音
            mAudioRecord.startRecording();
            //记录开始录音时间，用于统计时长
            mStartRecorderTime=System.currentTimeMillis();
            //循环读取数据，写道输出流中
            while (mIsRecording){
                //只要还在录音状态，就一直读取数据
                int read=mAudioRecord.read(mBuffer,0,BUFFER_SIZE);  //返回值为这次都到了多少
                if(read>0){
                    //读取成功及写到文件中
                    mFileOutputStream.write(mBuffer,0,read);
                }else{
                    //读取失败，返回false提示用户
                    return false;
                }

            }
            //退出循环，停止录音，释放资源
            return stopRecord();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            //捕获异常，避免闪退，返回false提醒用户失败
            return false;
        }   finally {
            //释放AudioRecord
            if(mAudioRecord!=null){
                mAudioRecord.release();
            }
        }
    }
    /**
     * 结束录音逻辑
     */
    private boolean stopRecord() {
        try {
            //停止录音，关闭文件输出流
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord=null;
            mFileOutputStream.close();
            //记录结束时间，统计录音时长
            mStopRecorderTime=System.currentTimeMillis();
            //大于3秒才算成功，在主线程改变UI显示
            final int second= (int) ((mStopRecorderTime-mStartRecorderTime)/1000);
            if(second>3){
                //在主线程改UI，显示出来
                mMainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mTvLog.setText("\n录音成功"+second+"秒");
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
            //捕获异常，避免闪退，返回false提醒用户失败
            return false;
        }
        return true;
    }

    /**
     * 录音错误处理
     */
    private void recordFail() {
        //给用户toast提示失败，主要在主线程执行
        mMainThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(StreamActivity.this,"录音失败",Toast.LENGTH_SHORT).show();
                //重置录音状态，以及UI状态
                mIsRecording=false;
                mBtnStart.setText("开始");
            }
        });
    }
    public void btnStreamPlay(View v){
        //检查当前状态，防止重复播放
        if (mAudioFile != null && !mIsPlaying) {
            //设置当前播放状态
            mIsPlaying = true;
            //提交后台任务，防止阻塞主线程
            mExecutorService.submit(new Runnable() {
                @Override
                public void run() {
                    doPlay(mAudioFile);
                }
            });
        }
    }

    /**
     * 实际播放逻辑
     * @param audioFile
     */
    private void doPlay(File audioFile) {
        //配置播放器
        int streamType= AudioManager.STREAM_MUSIC;  //音乐类型，扬声器播放
        int sampleRate=44100;  //录音时采用的采样频率，所以播放时候使用同样的采用频率
        int channelCofig=AudioFormat.CHANNEL_OUT_MONO;   //MONO表示录音输入单声道，OUT_MONO为播放输出单声道
        int audioFormat=AudioFormat.ENCODING_PCM_16BIT;  //录音时使用16bit，所以播放时使用同样的格式
        int mode= AudioTrack.MODE_STREAM;   //流模式
        int minBufferSize=AudioTrack.getMinBufferSize(sampleRate,channelCofig,audioFormat);  //计算最小buffer大小
        AudioTrack audioTrack=new AudioTrack(streamType,sampleRate,channelCofig,audioFormat,Math.max(minBufferSize,BUFFER_SIZE),mode);  //不能小于AudioTrack的最低要求，也不能小于我们每次读的大小
        FileInputStream inputStream=null;   //从文件流读取数据
        try {
            inputStream=new FileInputStream(audioFile);
            //循环读数据，写道播放器去播放
            int read;
            //只要没读完，循环写播放
            audioTrack.play();
            while((read=inputStream.read(mBuffer))>0){
                int ret=audioTrack.write(mBuffer,0,read);
                //检查write返回值，错误处理
                switch (ret){
                    case AudioTrack.ERROR_INVALID_OPERATION:
                    case AudioTrack.ERROR_BAD_VALUE:
                    case AudioManager.ERROR_DEAD_OBJECT:
                        playFail();
                        return;
                    default:
                        break;
                }
            }
        }catch (RuntimeException | IOException e){
            e.printStackTrace();
            //错误处理，防止闪退
            playFail();
        }finally {
            mIsPlaying=false;
            //关闭文件输入流
            if(inputStream!=null){
                closeQuitly(inputStream);
            }
        }
        //播放器释放
        resetQuitly(audioTrack);


    }

    private void resetQuitly(AudioTrack audioTrack) {
        try {
            audioTrack.stop();
            audioTrack.release();
        }catch (RuntimeException e){
            e.printStackTrace();
        }
    }

    /**
     *  静默关闭输入流
     */
    private void closeQuitly(FileInputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
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
                Toast.makeText(StreamActivity.this, "播放失败", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
