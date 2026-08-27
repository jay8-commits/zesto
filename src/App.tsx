import React, { useState, useEffect, useCallback } from 'react';
import {
  ZestoTab,
  TransportProtocol,
  TargetProfile,
  CameraVirtualizationStatus,
  DiagnosticsLevel,
  Subsystem,
  BoundaryDiagnosticStage,
  FrameHealthState,
  ZestoUiState
} from './types.ts';
import {
  INITIAL_UI_STATE,
  exportDiagnosticsAsMarkdown
} from './services/telemetryEngine.ts';
import { Header } from './components/Header.tsx';
import { NavigationTabs } from './components/NavigationTabs.tsx';
import { StreamConfigScreen } from './components/StreamConfigScreen.tsx';
import { PreviewScreen } from './components/PreviewScreen.tsx';
import { DiagnosticsScreen } from './components/DiagnosticsScreen.tsx';
import { TargetCompatibilityScreen } from './components/TargetCompatibilityScreen.tsx';
import { ControlledCameraHarnessModal } from './components/ControlledCameraHarnessModal.tsx';
import { ModuleGuideModal } from './components/ModuleGuideModal.tsx';
import { ExportLogModal } from './components/ExportLogModal.tsx';

export const App: React.FC = () => {
  const [uiState, setUiState] = useState<ZestoUiState>(INITIAL_UI_STATE);
  const [isExportModalOpen, setIsExportModalOpen] = useState(false);

  const addLog = useCallback(
    (subsystem: Subsystem, level: DiagnosticsLevel, message: string, errorDetails?: string) => {
      setUiState((prev) => ({
        ...prev,
        eventLogs: [
          {
            timestampMs: Date.now(),
            subsystem,
            level,
            message,
            errorDetails
          },
          ...prev.eventLogs.slice(0, 199)
        ]
      }));
    },
    []
  );

  // Probe real browser media devices to populate hardware camera caps
  useEffect(() => {
    if (navigator.mediaDevices?.enumerateDevices) {
      navigator.mediaDevices
        .enumerateDevices()
        .then((devices) => {
          const videoInputs = devices.filter((d) => d.kind === 'videoinput');
          if (videoInputs.length > 0) {
            setUiState((prev) => ({
              ...prev,
              cameraCapabilities: {
                ...prev.cameraCapabilities,
                cameraCount: videoInputs.length
              }
            }));
            addLog(
              Subsystem.CAMERA_DETECTION,
              DiagnosticsLevel.INFO,
              `Enumerated ${videoInputs.length} hardware camera device(s) on host subsystem.`
            );
          }
        })
        .catch(() => {});
    }
  }, [addLog]);

  // Periodic Telemetry Simulator loop when stream is active
  useEffect(() => {
    if (!uiState.isConnected && !uiState.isDecoding) return;

    const interval = setInterval(() => {
      setUiState((prev) => {
        const packets = prev.diagnosticsSnapshot.streamStats.packetsReceived + 30;
        const bytes = prev.diagnosticsSnapshot.streamStats.bytesReceived + 45000;
        const decoded = prev.diagnosticsSnapshot.decodedFrames + 1;
        const delivered = prev.diagnosticsSnapshot.deliveredFrames + 1;
        const bitrate = 2400 + Math.sin(Date.now() * 0.002) * 200;

        return {
          ...prev,
          diagnosticsSnapshot: {
            ...prev.diagnosticsSnapshot,
            timestamp: Date.now(),
            decoderStatus: 'DECODING',
            decoderFps: prev.streamConfig.targetFps,
            decodedFrames: decoded,
            deliveredFrames: delivered,
            msSinceLastFrame: 33,
            frameHealthState: FrameHealthState.FRAME_ACTIVE,
            streamStats: {
              ...prev.diagnosticsSnapshot.streamStats,
              packetsReceived: packets,
              bytesReceived: bytes,
              estimatedBitrateKbps: bitrate
            },
            decoderStats: {
              ...prev.diagnosticsSnapshot.decoderStats,
              averageDecodeLatencyMs: 6.8 + Math.random() * 2.0
            }
          }
        };
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [uiState.isConnected, uiState.isDecoding]);

  // Handlers for Stream Config
  const handleUrlChange = (url: string) => {
    setUiState((prev) => ({
      ...prev,
      streamConfig: { ...prev.streamConfig, url },
      connectionTestResult: null
    }));
  };

  const handleProtocolChange = (protocol: TransportProtocol) => {
    setUiState((prev) => ({
      ...prev,
      streamConfig: { ...prev.streamConfig, protocol },
      diagnosticsSnapshot: {
        ...prev.diagnosticsSnapshot,
        transportType: protocol.replace('_', ' / ')
      }
    }));
    addLog(
      Subsystem.TRANSPORT,
      DiagnosticsLevel.INFO,
      `Transport protocol switched to ${protocol}`
    );
  };

  const handleResolutionChange = (width: number, height: number) => {
    setUiState((prev) => ({
      ...prev,
      streamConfig: { ...prev.streamConfig, targetWidth: width, targetHeight: height },
      diagnosticsSnapshot: {
        ...prev.diagnosticsSnapshot,
        decoderResolution: `${width}x${height}`
      }
    }));
    addLog(
      Subsystem.DECODER,
      DiagnosticsLevel.INFO,
      `Target resolution set to ${width}x${height}`
    );
  };

  const handleFpsChange = (fps: number) => {
    setUiState((prev) => ({
      ...prev,
      streamConfig: { ...prev.streamConfig, targetFps: fps }
    }));
    addLog(
      Subsystem.DECODER,
      DiagnosticsLevel.INFO,
      `Target framerate set to ${fps} FPS`
    );
  };

  const handleSourceTypeChange = (source: 'simulated_rtsp' | 'webcam' | 'test_pattern') => {
    setUiState((prev) => ({ ...prev, inputSourceType: source }));
    addLog(
      Subsystem.FRAME_PIPELINE,
      DiagnosticsLevel.INFO,
      `Video source carrier switched to: ${source}`
    );
  };

  // Test Connection Probe
  const handleTestConnection = () => {
    const url = uiState.streamConfig.url.trim();
    if (!url) return;

    setUiState((prev) => ({
      ...prev,
      isTestingConnection: true,
      connectionTestResult: null
    }));

    addLog(
      Subsystem.TRANSPORT,
      DiagnosticsLevel.INFO,
      `Initiating RTSP probe handshake for: ${url}`
    );

    setTimeout(() => {
      const isFakeValid = url.startsWith('rtsp://') || url.startsWith('http');
      if (isFakeValid) {
        setUiState((prev) => ({
          ...prev,
          isTestingConnection: false,
          connectionTestResult: `SUCCESS: Reachable RTSP stream at ${url} (Audio/Video tracks detected, H.264 Main Profile)`
        }));
        addLog(
          Subsystem.TRANSPORT,
          DiagnosticsLevel.INFO,
          `RTSP probe response: DESCRIBE & SETUP negotiation succeeded (200 OK)`
        );
      } else {
        setUiState((prev) => ({
          ...prev,
          isTestingConnection: false,
          connectionTestResult: `ERROR: Invalid RTSP URL schema. Must start with rtsp://`
        }));
        addLog(
          Subsystem.TRANSPORT,
          DiagnosticsLevel.ERROR,
          `RTSP probe failed: Invalid protocol prefix`,
          'Protocol URL format error'
        );
      }
    }, 1000);
  };

  // Connect / Disconnect
  const handleConnect = () => {
    setUiState((prev) => ({
      ...prev,
      isConnecting: true,
      connectionTestResult: null
    }));

    addLog(
      Subsystem.TRANSPORT,
      DiagnosticsLevel.INFO,
      `Connecting to RTSP endpoint ${uiState.streamConfig.url}...`
    );

    setTimeout(() => {
      setUiState((prev) => ({
        ...prev,
        isConnecting: false,
        isConnected: true,
        isDecoding: true,
        diagnosticsSnapshot: {
          ...prev.diagnosticsSnapshot,
          transportStatus: 'CONNECTED',
          decoderStatus: 'DECODING',
          pipelineStatus: 'ACTIVE',
          frameHealthState: FrameHealthState.FRAME_ACTIVE,
          activeBoundaries: Array.from(
            new Set([
              ...prev.diagnosticsSnapshot.activeBoundaries,
              BoundaryDiagnosticStage.RTSP_CONNECTED,
              BoundaryDiagnosticStage.VIDEO_TRACK_DETECTED,
              BoundaryDiagnosticStage.DECODER_INITIALIZED,
              BoundaryDiagnosticStage.FRAME_RECEIVED,
              BoundaryDiagnosticStage.VIDEO_FRAME_DECODED,
              BoundaryDiagnosticStage.FRAME_CONVERTED,
              BoundaryDiagnosticStage.FRAME_BRIDGE_POSTED,
              BoundaryDiagnosticStage.FRAME_SUBSTITUTION_ACTIVE,
              BoundaryDiagnosticStage.TARGET_PREVIEW_RECEIVED_FRAME
            ])
          )
        }
      }));

      addLog(
        Subsystem.TRANSPORT,
        DiagnosticsLevel.INFO,
        `RTSP session established. Video pipeline streaming active.`
      );
      addLog(
        Subsystem.DECODER,
        DiagnosticsLevel.INFO,
        `MediaCodec hardware H.264 video decoder configured for ${uiState.streamConfig.targetWidth}x${uiState.streamConfig.targetHeight}`
      );
    }, 800);
  };

  const handleDisconnect = () => {
    setUiState((prev) => ({
      ...prev,
      isConnected: false,
      isDecoding: false,
      isConnecting: false,
      diagnosticsSnapshot: {
        ...prev.diagnosticsSnapshot,
        transportStatus: 'DISCONNECTED',
        decoderStatus: 'UNINITIALIZED',
        pipelineStatus: 'IDLE',
        frameHealthState: FrameHealthState.NO_FRAME,
        decoderFps: 0
      }
    }));

    addLog(
      Subsystem.TRANSPORT,
      DiagnosticsLevel.INFO,
      `RTSP transport disconnected by user`
    );
  };

  // Start / Stop Decoding from Preview
  const handleStartDecoding = () => {
    handleConnect();
  };

  const handleStopDecoding = () => {
    handleDisconnect();
  };

  // Service Toggle
  const handleToggleService = () => {
    setUiState((prev) => {
      const nextState = !prev.isServiceRunning;
      return {
        ...prev,
        isServiceRunning: nextState,
        diagnosticsSnapshot: {
          ...prev.diagnosticsSnapshot,
          serviceRuntimeState: nextState ? 'SERVICE_RUNNING (FOREGROUND)' : 'SERVICE_STOPPED'
        }
      };
    });

    addLog(
      Subsystem.SYSTEM,
      DiagnosticsLevel.INFO,
      uiState.isServiceRunning
        ? 'Stopped ZestoStreamingService foreground daemon'
        : 'Started ZestoStreamingService foreground daemon for IPC surface frame delivery'
    );
  };

  // Select Target Profile
  const handleSelectProfile = (profile: TargetProfile) => {
    setUiState((prev) => ({
      ...prev,
      selectedTargetProfile: profile,
      diagnosticsSnapshot: {
        ...prev.diagnosticsSnapshot,
        targetPackage: profile.packageName,
        activeBackend: profile.supportedBackend
      }
    }));

    addLog(
      Subsystem.TARGET_COMPATIBILITY,
      DiagnosticsLevel.INFO,
      `Active target profile switched to: ${profile.appName} (${profile.packageName})`
    );
  };

  // Run Profile Test
  const handleRunProfileTest = (profile: TargetProfile) => {
    setUiState((prev) => ({
      ...prev,
      targetProfiles: prev.targetProfiles.map((p) =>
        p.id === profile.id
          ? { ...p, testStatus: CameraVirtualizationStatus.TESTING }
          : p
      )
    }));

    addLog(
      Subsystem.TARGET_COMPATIBILITY,
      DiagnosticsLevel.INFO,
      `Executing live bytecode & manifest inspection for ${profile.appName}...`
    );

    setTimeout(() => {
      setUiState((prev) => ({
        ...prev,
        targetProfiles: prev.targetProfiles.map((p) =>
          p.id === profile.id
            ? { ...p, testStatus: CameraVirtualizationStatus.SUPPORTED }
            : p
        )
      }));

      addLog(
        Subsystem.TARGET_COMPATIBILITY,
        DiagnosticsLevel.INFO,
        `Target application inspection passed for ${profile.appName}: Camera2 createCaptureSession hooks verified.`
      );
    }, 1200);
  };

  const handleExportLogs = () => {
    const markdown = exportDiagnosticsAsMarkdown(
      uiState.diagnosticsSnapshot,
      uiState.eventLogs
    );
    setUiState((prev) => ({ ...prev, exportedLogText: markdown }));
    setIsExportModalOpen(true);
  };

  return (
    <div className="min-h-screen bg-[#1A1C1E] text-[#E2E2E6] flex flex-col selection:bg-[#4F378B] selection:text-[#EADDFF]">
      {/* Sticky Top Header */}
      <Header
        uiState={uiState}
        onOpenHarness={() =>
          setUiState((prev) => ({ ...prev, showTestHarnessModal: true }))
        }
      />

      {/* Main Content Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 md:p-8">
        {uiState.selectedTab === ZestoTab.STREAM_CONFIG && (
          <StreamConfigScreen
            uiState={uiState}
            onUrlChange={handleUrlChange}
            onProtocolChange={handleProtocolChange}
            onResolutionChange={handleResolutionChange}
            onFpsChange={handleFpsChange}
            onSourceTypeChange={handleSourceTypeChange}
            onTestConnection={handleTestConnection}
            onConnect={handleConnect}
            onDisconnect={handleDisconnect}
          />
        )}

        {uiState.selectedTab === ZestoTab.STREAM_PREVIEW && (
          <PreviewScreen
            uiState={uiState}
            onStartDecoding={handleStartDecoding}
            onStopDecoding={handleStopDecoding}
          />
        )}

        {uiState.selectedTab === ZestoTab.DIAGNOSTICS && (
          <DiagnosticsScreen
            snapshot={uiState.diagnosticsSnapshot}
            events={uiState.eventLogs}
            onExportLogs={handleExportLogs}
          />
        )}

        {uiState.selectedTab === ZestoTab.TARGET_COMPAT && (
          <TargetCompatibilityScreen
            uiState={uiState}
            onSelectProfile={handleSelectProfile}
            onToggleService={handleToggleService}
            onOpenTestHarness={() =>
              setUiState((prev) => ({ ...prev, showTestHarnessModal: true }))
            }
            onOpenModuleGuide={() =>
              setUiState((prev) => ({ ...prev, showModuleGuideDialog: true }))
            }
            onRunProfileTest={handleRunProfileTest}
          />
        )}
      </main>

      {/* Persistent Navigation Tabs (Fixed Bottom on mobile, relative on desktop) */}
      <NavigationTabs
        selectedTab={uiState.selectedTab}
        onSelectTab={(tab) => setUiState((prev) => ({ ...prev, selectedTab: tab }))}
      />

      {/* Controlled Camera Test Harness Modal */}
      <ControlledCameraHarnessModal
        isOpen={uiState.showTestHarnessModal}
        onClose={() =>
          setUiState((prev) => ({ ...prev, showTestHarnessModal: false }))
        }
        targetPackage={uiState.selectedTargetProfile?.packageName}
      />

      {/* LSPatch & Module Guide Modal */}
      <ModuleGuideModal
        isOpen={uiState.showModuleGuideDialog}
        onClose={() =>
          setUiState((prev) => ({ ...prev, showModuleGuideDialog: false }))
        }
      />

      {/* Export Diagnostics Modal */}
      <ExportLogModal
        isOpen={isExportModalOpen}
        onClose={() => setIsExportModalOpen(false)}
        logText={uiState.exportedLogText || ''}
      />
    </div>
  );
};
