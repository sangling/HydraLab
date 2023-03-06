// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.

package com.microsoft.hydralab.agent.runner.t2c;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.img.gif.AnimatedGifEncoder;
import com.microsoft.hydralab.agent.runner.TestTaskRunCallback;
import com.microsoft.hydralab.agent.runner.appium.AppiumRunner;
import com.microsoft.hydralab.common.entity.common.AndroidTestUnit;
import com.microsoft.hydralab.common.entity.common.DeviceCombo;
import com.microsoft.hydralab.common.entity.common.DeviceInfo;
import com.microsoft.hydralab.common.entity.common.TestRun;
import com.microsoft.hydralab.common.entity.common.TestTask;
import com.microsoft.hydralab.common.logger.LogCollector;
import com.microsoft.hydralab.common.management.AgentManagementService;
import com.microsoft.hydralab.common.management.device.TestDeviceManager;
import com.microsoft.hydralab.common.screen.FFmpegConcatUtil;
import com.microsoft.hydralab.common.screen.ScreenRecorder;
import com.microsoft.hydralab.common.util.Const;
import com.microsoft.hydralab.performance.PerformanceTestManagementService;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class T2CRunner extends AppiumRunner {

    private final AnimatedGifEncoder e = new AnimatedGifEncoder();
    private LogCollector logCollector;
    private ScreenRecorder deviceScreenRecorder;
    private String pkgName;
    String agentName;
    private int currentIndex = 0;

    // todo workaround for E2E agent
    TestDeviceManager comboDeviceManager;
    ScreenRecorder comboDeviceScreenRecorder;
    DeviceInfo comboDeviceInfo;

    public T2CRunner(AgentManagementService agentManagementService, TestTaskRunCallback testTaskRunCallback,
                     PerformanceTestManagementService performanceTestManagementService, String agentName) {
        super(agentManagementService, testTaskRunCallback, performanceTestManagementService);
        this.agentName = agentName;
    }

    @Override
    protected File runAndGetGif(File initialJsonFile, String unusedSuiteName, DeviceInfo deviceInfo,
                                TestTask testTask,
                                TestRun testRun, File deviceTestResultFolder, Logger reportLogger) {
        pkgName = testTask.getPkgName();

        //todo workaround for E2E agent
        if (deviceInfo instanceof DeviceCombo) {
            comboDeviceInfo = ((DeviceCombo) deviceInfo).getLinkedDeviceInfo();
            comboDeviceManager = comboDeviceInfo.getTestDeviceManager();
            comboDeviceScreenRecorder =
                    comboDeviceManager.getScreenRecorder(comboDeviceInfo, testRun.getResultFolder(), reportLogger);
        }

        // Test start
        deviceScreenRecorder =
                testDeviceManager.getScreenRecorder(deviceInfo, deviceTestResultFolder, reportLogger);
        deviceScreenRecorder.setupDevice();
        deviceScreenRecorder.startRecord(testTask.getTimeOutSecond());

        // todo workaround for E2E agent
        if (comboDeviceScreenRecorder != null) {
            comboDeviceScreenRecorder.setupDevice();
            comboDeviceScreenRecorder.startRecord(testTask.getTimeOutSecond());
        }

        long recordingStartTimeMillis = System.currentTimeMillis();

        logCollector = testDeviceManager.getLogCollector(deviceInfo, pkgName, testRun, reportLogger);
        logCollector.start();

        testRun.setTotalCount(testTask.testJsonFileList.size() + (initialJsonFile == null ? 0 : 1));
        testRun.setTestStartTimeMillis(System.currentTimeMillis());
        testRun.addNewTimeTag("testRunStarted", System.currentTimeMillis() - recordingStartTimeMillis);

        performanceTestManagementService.testRunStarted();

        deviceInfo.setRunningTestName(pkgName.substring(pkgName.lastIndexOf('.') + 1) + ".testRunStarted");
        currentIndex = 0;

        File gifFile = new File(testRun.getResultFolder(), pkgName + ".gif");
        e.start(gifFile.getAbsolutePath());
        e.setDelay(1000);
        e.setRepeat(0);

        if (initialJsonFile != null) {
            runT2CJsonTestCase(initialJsonFile, deviceInfo, testRun, reportLogger, recordingStartTimeMillis);
        }
        for (File jsonFile : testTask.testJsonFileList) {
            runT2CJsonTestCase(jsonFile, deviceInfo, testRun, reportLogger, recordingStartTimeMillis);
        }

        // Test finish
        reportLogger.info(pkgName + ".end");
        performanceTestManagementService.testRunFinished();
        testRun.addNewTimeTag("testRunEnded", System.currentTimeMillis() - recordingStartTimeMillis);
        testRun.onTestEnded();
        deviceInfo.setRunningTestName(null);
        releaseResource(testRun, reportLogger);
        return gifFile;
    }

    private void runT2CJsonTestCase(File jsonFile, DeviceInfo deviceInfo, TestRun testRun,
                                    Logger reportLogger, long recordingStartTimeMillis) {
        AndroidTestUnit ongoingTest = new AndroidTestUnit();
        ongoingTest.setNumtests(testRun.getTotalCount());
        ongoingTest.setStartTimeMillis(System.currentTimeMillis());
        ongoingTest.setRelStartTimeInVideo(ongoingTest.getStartTimeMillis() - recordingStartTimeMillis);
        ongoingTest.setCurrentIndexNum(currentIndex++);
        ongoingTest.setTestName(jsonFile.getName());
        ongoingTest.setTestedClass(pkgName);
        ongoingTest.setDeviceTestResultId(testRun.getId());
        ongoingTest.setTestTaskId(testRun.getTestTaskId());

        reportLogger.info(ongoingTest.getTitle());

        testRun.addNewTimeTag(currentIndex + ". " + ongoingTest.getTitle(),
                System.currentTimeMillis() - recordingStartTimeMillis);
        testRun.addNewTestUnit(ongoingTest);

        performanceTestManagementService.testStarted(ongoingTest.getTitle());

        testDeviceManager.updateScreenshotImageAsyncDelay(deviceInfo, TimeUnit.SECONDS.toMillis(5),
                (imagePNGFile -> {
                    if (imagePNGFile == null || !e.isStarted()) {
                        return;
                    }
                    try {
                        e.addFrame(ImgUtil.toBufferedImage(ImgUtil.scale(ImageIO.read(imagePNGFile), 0.3f)));
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                }), reportLogger);

        // Run Test
        try {
            testDeviceManager.runAppiumT2CTest(deviceInfo, jsonFile, reportLogger);
            performanceTestManagementService.testSuccess(ongoingTest.getTitle());
            ongoingTest.setStatusCode(AndroidTestUnit.StatusCodes.OK);
            ongoingTest.setSuccess(true);
        } catch (Exception e) {
            // Fail
            ongoingTest.setStatusCode(AndroidTestUnit.StatusCodes.FAILURE);
            ongoingTest.setSuccess(false);
            ongoingTest.setStack(e.toString());
            performanceTestManagementService.testFailure(ongoingTest.getTitle());
            testRun.addNewTimeTag(ongoingTest.getTitle() + ".fail",
                    System.currentTimeMillis() - recordingStartTimeMillis);
            testRun.oneMoreFailure();
        }
        ongoingTest.setEndTimeMillis(System.currentTimeMillis());
        ongoingTest.setRelEndTimeInVideo(ongoingTest.getEndTimeMillis() - recordingStartTimeMillis);
        testRun.addNewTimeTag(ongoingTest.getTitle() + ".end",
                System.currentTimeMillis() - recordingStartTimeMillis);
    }

    private void releaseResource(TestRun testRun, Logger logger) {
        e.finish();
        deviceScreenRecorder.finishRecording();
        // todo workaround for E2E agent
        if (comboDeviceScreenRecorder != null) {
            comboDeviceScreenRecorder.finishRecording();
            File phoneVideoFile = new File(testRun.getResultFolder().getAbsolutePath(),
                    Const.ScreenRecoderConfig.DEFAULT_FILE_NAME);
            File pcVideoFile =
                    new File(testRun.getResultFolder().getAbsolutePath(), Const.ScreenRecoderConfig.PC_FILE_NAME);
            if (pcVideoFile.exists() && phoneVideoFile.exists()) {
                // Merge two videos side-by-side if exist
                System.out.println("-------------Merge two videos side-by-side-------------");
                File tempVideoFile = new File(testRun.getResultFolder().getAbsolutePath(),
                        Const.ScreenRecoderConfig.TEMP_FILE_NAME);
                FFmpegConcatUtil.mergeVideosSideBySide(phoneVideoFile.getAbsolutePath(),
                        pcVideoFile.getAbsolutePath(), tempVideoFile.getAbsolutePath(), logger);
                // Rename phone video file
                phoneVideoFile.renameTo(new File(testRun.getResultFolder().getAbsolutePath(),
                        Const.ScreenRecoderConfig.PHONE_FILE_NAME));
                // Rename temp video file
                tempVideoFile.renameTo(new File(testRun.getResultFolder().getAbsolutePath(),
                        Const.ScreenRecoderConfig.DEFAULT_FILE_NAME));
            }
        }
        logCollector.stopAndAnalyse();
    }
}
