package com.younchen.thread;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;

/**
 * Created by 龙泉 on 2016/12/5.
 */

public class ThreadTest {

    @Test
    public void testThread() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch taskLatch = new CountDownLatch(1);

        final Object lock = new Object();
        final Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        Thread.sleep(5000);
                        lock.notifyAll();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    try {
                        taskLatch.countDown();
                        lock.wait();
                        while (true) {
                            System.out.println("t2 running");
                            Thread.sleep(1000);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });


        t2.start();
        taskLatch.await();
        t1.start();
        latch.await();

    }

}
