import {
  BoundaryDiagnosticStage,
  CameraApiType,
  CameraCapabilities,
  CameraVirtualizationStatus,
  DiagnosticsEvent,
  DiagnosticsLevel,
  DiagnosticsSnapshot,
  FrameHealthState,
  StreamConfig,
  Subsystem,
  TransportProtocol,
  ZestoTab,
  ZestoUiState
} from '../types.ts';
import { INITIAL_PROFILES } from './profilesData.ts';

export const DEFAULT_STREAM_CONFIG: StreamConfig = {
  url: 'rtsp://192.168.1.104:8554/live',
  protocol: TransportProtocol.RTSP_TCP,
  targetWidth: 1280,
  targetHeight: 720,
  targetFps: 30,
  connectionTimeoutMs: 5000,
  readTimeoutMs: 5000,
  autoReconnect: true,
  maxReconnectAttempts: 5,
  reconnectDelayMs: 2000,
  bufferDurationMs: 200
};

export const INITIAL_CAMERA_CAPS: CameraCapabilities = {
  apiType: CameraApiType.CAMERA2,
  hardwareLevel: 'LEVEL_3 / FULL (Hardware Accelerated)',
  supportedResolutions: ['1920x1080', '1280x720', '640x480'],
  supportedFpsRanges: ['[15, 30]', '[30, 30]', '[30, 60]'],
  supportsSurfaceTexture: true,
  supportsImageReader: true,
  requiresInstrumentation: false,
  cameraCount: 2
};

export const INITIAL_SNAPSHOT: DiagnosticsSnapshot = {
  timestamp: Date.now(),
  transportStatus: 'DISCONNECTED',
  transportType: 'RTSP / TCP',
  rtspUrl: DEFAULT_STREAM_CONFIG.url,
  reconnectCount: 0,
  streamStats: {
    bytesReceived: 0,
    packetsReceived: 0,
    packetsLost: 0,
    estimatedBitrateKbps: 0,
    jitterMs: 0,
    roundTripTimeMs: 0
  },
  decoderStatus: 'UNINITIALIZED',
  decoderResolution: '1280x720',
  decoderFps: 0.0,
  decodedFrames: 0,
  decoderDroppedFrames: 0,
  decodeErrors: 0,
  decoderStats: {
    averageDecodeLatencyMs: 8.4,
    maxDecodeLatencyMs: 14.2,
    keyFramesDecoded: 0,
    droppedFrames: 0,
    retainedBuffers: 3,
    hwAccelerated: true
  },
  pipelineStatus: 'IDLE',
  frameHealthState: FrameHealthState.NO_FRAME,
  msSinceLastFrame: -1,
  deliveredFrames: 0,
  pipelineDroppedFrames: 0,
  pipelineLatencyMs: 0,
  activeConsumers: 2,
  pipelineStats: {
    queueSize: 0,
    deliveredFrames: 0,
    droppedFrames: 0,
    averageProcessingTimeMs: 4.1,
    maxProcessingTimeMs: 9.0,
    lastFrameTimestampMs: 0
  },
  serviceRuntimeState: 'SERVICE_STOPPED',
  ipcProviderStatus: 'ONLINE (AUTHORITY: com.example.zesto.frameprovider)',
  detectedCameraApi: CameraApiType.CAMERA2,
  cameraHardwareLevel: 'LEVEL_3 (Full Camera2 Hardware Level)',
  activeBackend: 'Camera2Backend',
  virtualizationStatus: CameraVirtualizationStatus.NOT_TESTED,
  targetPackage: 'net.sourceforge.opencamera',
  targetStatus: 'CONFIGURED',
  activeBoundaries: [
    BoundaryDiagnosticStage.PATCH_MANIFEST_APPLICATION,
    BoundaryDiagnosticStage.CAMERA2_HOOK_INSTALLED
  ]
};

export const INITIAL_UI_STATE: ZestoUiState = {
  selectedTab: ZestoTab.STREAM_CONFIG,
  streamConfig: DEFAULT_STREAM_CONFIG,
  isConnecting: false,
  isConnected: false,
  isDecoding: false,
  isServiceRunning: false,
  isVirtualFeedActive: false,
  connectionTestResult: null,
  isTestingConnection: false,
  diagnosticsSnapshot: INITIAL_SNAPSHOT,
  eventLogs: [
    {
      timestampMs: Date.now() - 3200,
      subsystem: Subsystem.SYSTEM,
      level: DiagnosticsLevel.INFO,
      message: 'Zesto Authoritative RTSP Stream & Virtualization Pipeline Initialized'
    },
    {
      timestampMs: Date.now() - 2500,
      subsystem: Subsystem.CAMERA_DETECTION,
      level: DiagnosticsLevel.INFO,
      message: 'Detected Hardware Camera2 subsystem with SurfaceTexture direct output support'
    },
    {
      timestampMs: Date.now() - 1800,
      subsystem: Subsystem.FRAME_PIPELINE,
      level: DiagnosticsLevel.INFO,
      message: 'ZestoFrameBridge registered default bridge frame consumer'
    },
    {
      timestampMs: Date.now() - 1100,
      subsystem: Subsystem.TARGET_COMPATIBILITY,
      level: DiagnosticsLevel.INFO,
      message: 'Active profile set to Open Camera (net.sourceforge.opencamera) -> Camera2Backend'
    }
  ],
  cameraCapabilities: INITIAL_CAMERA_CAPS,
  targetProfiles: INITIAL_PROFILES,
  selectedTargetProfile: INITIAL_PROFILES[0],
  profileSearchQuery: '',
  showModuleGuideDialog: false,
  showTestHarnessModal: false,
  exportedLogText: null,
  userNoticeMessage: null,
  inputSourceType: 'test_pattern'
};

export function exportDiagnosticsAsMarkdown(
  snapshot: DiagnosticsSnapshot,
  logs: DiagnosticsEvent[]
): string {
  const dateStr = new Date(snapshot.timestamp).toISOString().replace('T', ' ').slice(0, 23);
  const lines: string[] = [];

  lines.push('==================================================');
  lines.push('ZESTO DIAGNOSTICS LOG EXPORT');
  lines.push(`Generated At: ${dateStr}`);
  lines.push('==================================================');
  lines.push('');

  lines.push('--- TRANSPORT LAYER ---');
  lines.push(`Status: ${snapshot.transportStatus}`);
  lines.push(`Type: ${snapshot.transportType}`);
  lines.push(`URL: ${snapshot.rtspUrl}`);
  lines.push(`Reconnects: ${snapshot.reconnectCount}`);
  lines.push(`Packets Received: ${snapshot.streamStats.packetsReceived}`);
  lines.push(`Bitrate: ${snapshot.streamStats.estimatedBitrateKbps.toFixed(1)} kbps`);
  lines.push('');

  lines.push('--- VIDEO DECODER LAYER ---');
  lines.push(`Status: ${snapshot.decoderStatus}`);
  lines.push(`Resolution: ${snapshot.decoderResolution}`);
  lines.push(`FPS: ${snapshot.decoderFps.toFixed(1)}`);
  lines.push(`Decoded Frames: ${snapshot.decodedFrames}`);
  lines.push(`Dropped Frames: ${snapshot.decoderDroppedFrames}`);
  lines.push(`Decode Errors: ${snapshot.decodeErrors}`);
  lines.push(`Decode Latency: ${snapshot.decoderStats.averageDecodeLatencyMs.toFixed(1)} ms`);
  lines.push('');

  lines.push('--- FRAME PIPELINE LAYER ---');
  lines.push(`Status: ${snapshot.pipelineStatus}`);
  lines.push(`Delivered: ${snapshot.deliveredFrames}`);
  lines.push(`Dropped: ${snapshot.pipelineDroppedFrames}`);
  lines.push(`Latency: ${snapshot.pipelineLatencyMs} ms`);
  lines.push(`Active Consumers: ${snapshot.activeConsumers}`);
  lines.push('');

  lines.push('--- CAMERA API LAYER ---');
  lines.push(`Detected API: ${snapshot.detectedCameraApi}`);
  lines.push(`Hardware Level: ${snapshot.cameraHardwareLevel}`);
  lines.push('');

  lines.push('--- VIRTUALIZATION BACKEND ---');
  lines.push(`Backend: ${snapshot.activeBackend}`);
  lines.push(`Status: ${snapshot.virtualizationStatus}`);
  lines.push('');

  lines.push('--- TARGET APPLICATION ---');
  lines.push(`Target Package: ${snapshot.targetPackage}`);
  lines.push(`Target Status: ${snapshot.targetStatus}`);
  lines.push('');

  lines.push('--- PIPELINE BOUNDARY MILESTONES ---');
  const allBoundaries = Object.values(BoundaryDiagnosticStage);
  allBoundaries.forEach((stage) => {
    const achieved = snapshot.activeBoundaries.includes(stage as BoundaryDiagnosticStage);
    const mark = achieved ? '[X]' : '[ ]';
    lines.push(`${mark} ${stage}`);
  });
  lines.push('');

  if (snapshot.faultSubsystem) {
    lines.push('--- ACTIVE FAULT IDENTIFICATION ---');
    lines.push(`Faulting Subsystem: ${snapshot.faultSubsystem}`);
    lines.push(`Error: ${snapshot.lastErrorMessage || 'Unknown'}`);
    lines.push('');
  }

  lines.push(`--- EVENT LOG (${logs.length} entries) ---`);
  if (logs.length === 0) {
    lines.push('(No logged events)');
  } else {
    logs.forEach((log) => {
      const time = new Date(log.timestampMs).toTimeString().split(' ')[0] + '.' + String(log.timestampMs % 1000).padStart(3, '0');
      lines.push(`[${time}] [${log.level}] [${log.subsystem}] ${log.message}`);
      if (log.errorDetails) {
        lines.push(`    Details: ${log.errorDetails}`);
      }
    });
  }
  lines.push('==================================================');

  return lines.join('\n');
}
