package com.younchen;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import junit.framework.Assert;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * Created by 龙泉 on 2016/12/5.
 */

public class AudioEncoderTest {

    private static final String TAG = "AudioEncoderTest";

    private volatile boolean mIsRecording = false;
    private volatile boolean mRequestStop = false;

    private boolean mIsEOS = false;
    private final int TIMEOUT_USEC = 10000;

    private MediaCodec mMediaCodec;

    private MediaMuxer muxer;


    private MediaCodec.BufferInfo mBufferInfo;		// API >= 16(Android4.1.2)
    private long prevOutputPTSUs = 0;
    private int mTrackIndex;


    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;    // 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;
    public static final int SAMPLES_PER_FRAME = 1024;    // AAC, bytes/frame/channel
    public static final int FRAMES_PER_BUFFER = 25;    // AAC, frame/buffer/sec


    final Object lock = new Object();

    private EncoderThread encoderThread;
    private AudioSourceReadThread audioSourceReadThread;

    private int needDrain;


    boolean DEBUG = true;

    CountDownLatch latch = new CountDownLatch(1);
    private boolean mMuxerStarted;
    private boolean mMuxerIsRunning;

    private String mOutputPath = "/sdcard/youn.mp3";
    private String mInputPath = "/sdcard/rock.mp3";

    private File inputFile;
    private File outputFile;

    @Test
    public void testAudioEncoding() throws IOException, InterruptedException {

        initInputFile();
        //create Codec
        initMediaCodec();
        //
        initMuxer();
        startRecord();
        latch.await();
    }

    private void initInputFile() {
        inputFile = new File(mInputPath);
        outputFile = new File(mOutputPath);
        Assert.assertEquals(true, inputFile.exists());
    }

    private void startRecord() {
        mIsRecording = true;
        needDrain = 0;
        encoderThread = new EncoderThread();
        audioSourceReadThread = new AudioSourceReadThread();
        encoderThread.start();
        audioSourceReadThread.start();
    }

    private void initMuxer() throws IOException {
        muxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private void stopRecording(){
        mIsRecording = false;
        mRequestStop = true;
    }

    private void initMediaCodec() throws IOException {
        final MediaFormat audioFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
//		audioFormat.setLong(MediaFormat.KEY_MAX_INPUT_SIZE, inputFile.length());
//        audioFormat.setLong(MediaFormat.KEY_DURATION, (long)durationInMs );
        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();
    }


    private void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        if (!mIsRecording) return;
        final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsRecording) {
            final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                if (buffer != null) {
                    inputBuffer.put(buffer);
                }
//	            if (DEBUG) Log.v(TAG, "encode:queueInputBuffer");
                if (length <= 0) {
                    // send EOS
                    mIsEOS = true;
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
                            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                } else {
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length,
                            presentationTimeUs, 0);
                }
                break;
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait for MediaCodec encoder is ready to encode
                // nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
                // will wait for maximum TIMEOUT_USEC(10msec) on each call
            }
        }
    }

    private class EncoderThread extends Thread {

        public EncoderThread(){

        }

        @Override
        public void run() {

            synchronized (lock) {
                mRequestStop = false;
                needDrain = 0;
                lock.notify();
            }

            final boolean isRunning = true;
            boolean localRequestStop;
            boolean localRequestDrain;

            while (isRunning) {

                synchronized (lock) {
                    System.out.println("loop remaind drain:" + needDrain);
                    localRequestStop = mRequestStop;
                    localRequestDrain = needDrain > 0;
                    if (localRequestDrain) {
                        needDrain--;
                    }
                }
                if (localRequestStop) {
                    //
                    System.out.println("-------end drain");
                    drain();
                    //write eof
                    writeEof();
                    //
                    drain();
                    System.out.println("-------end drain");
                }
                if (localRequestDrain) {
                    drain();
                } else {
                    try {
                        synchronized (lock){
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void writeEof() {
//        encode(null, 0, getPTSUs());
        System.out.println("write eof");
    }

    private class AudioSourceReadThread extends Thread {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            try {

                if (mIsRecording) {

                    final ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
                    int readBytes;
//                    audioRecord.startRecording();
                    FileInputStream in = new FileInputStream(inputFile);
                    byte[] readData = new byte[SAMPLES_PER_FRAME];
                    int readCount =0 ;
                    try {
                        while ((readCount = in.read(readData)) != -1 && mIsRecording && !mIsEOS) {
                            buf.clear();
                            buf.put(readData, 0, readCount);
                            if (readCount > 0) {
                                // set audio data to encoder
                                buf.position(readCount);
                                buf.flip();
                                encode(buf, readCount, getPTSUs());
                                frameAvailableSoon();
                            }
                        }
                        frameAvailableSoon();

//
//                        for (; mIsRecording && !mRequestStop && !mIsEOS; ) {
//                            // read audio data from internal mic
//                            buf.clear();
//                            buf.put(read,0,readCount);
//                            readBytes = audioRecord.read(buf, SAMPLES_PER_FRAME);
//                            if (readBytes > 0) {
//
//                            }
//                        }

                    } finally {
                        stopRecording();
                    }
                }
            } catch (Exception ex) {
            } finally {
                mIsRecording = false;
            }
        }
    }


    private void frameAvailableSoon() {
        synchronized (lock) {
            if (mIsRecording && !mRequestStop) {
                needDrain++;
                lock.notifyAll();
            }
        }
    }

    private long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        // presentationTimeUs should be monotonic
        // otherwise muxer fail to write
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }

    protected void drain() {
        System.out.println("drain");
        if (mMediaCodec == null) return;
        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;
        if (muxer == null) {
//        	throw new NullPointerException("muxer is unexpectedly null");
            Log.w(TAG, "muxer is unexpectedly null");
            return;
        }
        LOOP:	while (mIsRecording) {
            // get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
            encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                    if (++count > 5)
                        break LOOP;		// out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (DEBUG) Log.v(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                // this shoud not come when encoding
                encoderOutputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (DEBUG) Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
                // this status indicate the output format of codec is changed
                // this should come only once before actual encoded data
                // but this status never come on Android4.3 or less
                // and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
                if (mMuxerStarted) {	// second time request is error
                    throw new RuntimeException("format changed twice");
                }
                // get output format from codec and pass them to muxer
                // getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
                final MediaFormat format = mMediaCodec.getOutputFormat(); // API >= 16
                mTrackIndex = muxer.addTrack(format);
                mMuxerStarted = true;
                if (!startMuxer()) {
                    // we should wait until muxer is ready
                    synchronized (muxer) {
                        while (!mMuxerIsRunning)
                            try {
                                muxer.wait(100);
                            } catch (final InterruptedException e) {
                                break LOOP;
                            }
                    }
                }
            } else if (encoderStatus < 0) {
                // unexpected status
                if (DEBUG) Log.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
            } else {
                final ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    // this never should come...may be a MediaCodec internal error
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // You shoud set output format to muxer here when you target Android4.3 or less
                    // but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                    // therefor we should expand and prepare output format from buffer data.
                    // This sample is for API>=18(>=Android 4.3), just ignore this flag here
                    if (DEBUG) Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // encoded data is ready, clear waiting counter
                    count = 0;
                    if (!mMuxerStarted) {
                        // muxer is not ready...this will prrograming failure.
                        throw new RuntimeException("drain:muxer hasn't started");
                    }
                    // write encoded data to muxer(need to adjust presentationTimeUs.
                    mBufferInfo.presentationTimeUs = getPTSUs();
                    muxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }
                // return buffer to encoder
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // when EOS come.
                    mIsRecording = false;
                    break;      // out of while
                }
            }
        }
    }

    private boolean startMuxer() {
        mMuxerIsRunning = true;
        muxer.notifyAll();
        return mMuxerIsRunning;
    }

}