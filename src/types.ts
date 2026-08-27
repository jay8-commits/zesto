export enum ZestoTab {
  STREAM_CONFIG = 'STREAM_CONFIG',
  STREAM_PREVIEW = 'STREAM_PREVIEW',
  DIAGNOSTICS = 'DIAGNOSTICS',
  TARGET_COMPAT = 'TARGET_COMPAT'
}

export enum TransportProtocol {
  RTSP_TCP = 'RTSP_TCP',
  RTSP_UDP = 'RTSP_UDP',
  FUTURE_WEBRTC = 'FUTURE_WEBRTC',
  FUTURE_USB = 'FUTURE_USB'
}

export enum CameraApiType {
  CAMERA1_LEGACY = 'CAMERA1_LEGACY',
  CAMERA2 = 'CAMERA2',
  CAMERAX = 'CAMERAX',
  NATIVE_NDK = 'NATIVE_NDK',
  UNKNOWN = 'UNKNOWN'
}

export enum CameraVirtualizationStatus {
  NOT_TESTED = 'NOT_TESTED',
  DETECTED = 'DETECTED',
  TESTING = 'TESTING',
  SUPPORTED = 'SUPPORTED',
  ACTIVE = 'ACTIVE',
  FAILED = 'FAILED',
  UNSUPPORTED = 'UNSUPPORTED',
  REQUIRES_ROOT = 'REQUIRES_ROOT',
  REQUIRES_INSTRUMENTATION = 'REQUIRES_INSTRUMENTATION',
  REQUIRES_PATCHING = 'REQUIRES_PATCHING'
}

export enum FrameHealthState {
  NO_FRAME = 'NO_FRAME',
  FRAME_ACTIVE = 'FRAME_ACTIVE',
  FRAME_STALLED = 'FRAME_STALLED'
}

export enum DiagnosticsLevel {
  DEBUG = 'DEBUG',
  INFO = 'INFO',
  WARNING = 'WARNING',
  ERROR = 'ERROR'
}

export enum Subsystem {
  TRANSPORT = 'TRANSPORT',
  DECODER = 'DECODER',
  FRAME_PIPELINE = 'FRAME_PIPELINE',
  CAMERA_DETECTION = 'CAMERA_DETECTION',
  VIRTUALIZATION = 'VIRTUALIZATION',
  TARGET_COMPATIBILITY = 'TARGET_COMPATIBILITY',
  SYSTEM = 'SYSTEM'
}

export enum BoundaryDiagnosticStage {
  RTSP_CONNECTED = 'RTSP_CONNECTED',
  VIDEO_TRACK_DETECTED = 'VIDEO_TRACK_DETECTED',
  DECODER_INITIALIZED = 'DECODER_INITIALIZED',
  FRAME_RECEIVED = 'FRAME_RECEIVED',
  VIDEO_FRAME_DECODED = 'VIDEO_FRAME_DECODED',
  FRAME_CONVERTED = 'FRAME_CONVERTED',
  FRAME_INJECTED = 'FRAME_INJECTED',
  FRAME_BRIDGE_POSTED = 'FRAME_BRIDGE_POSTED',
  PATCH_MANIFEST_APPLICATION = 'PATCH_MANIFEST_APPLICATION',
  TARGET_PROCESS_ATTACHED = 'TARGET_PROCESS_ATTACHED',
  CAMERA2_HOOK_INSTALLED = 'CAMERA2_HOOK_INSTALLED',
  CAMERA2_DEVICE_OPEN_INTERCEPTED = 'CAMERA2_DEVICE_OPEN_INTERCEPTED',
  FRAME_SUBSTITUTION_ACTIVE = 'FRAME_SUBSTITUTION_ACTIVE',
  FRAME_CONSUMED = 'FRAME_CONSUMED',
  TARGET_PREVIEW_RECEIVED_FRAME = 'TARGET_PREVIEW_RECEIVED_FRAME'
}

export interface StreamConfig {
  url: string;
  protocol: TransportProtocol;
  targetWidth: number;
  targetHeight: number;
  targetFps: number;
  connectionTimeoutMs: number;
  readTimeoutMs: number;
  autoReconnect: boolean;
  maxReconnectAttempts: number;
  reconnectDelayMs: number;
  bufferDurationMs: number;
}

export interface StreamStats {
  bytesReceived: number;
  packetsReceived: number;
  packetsLost: number;
  estimatedBitrateKbps: number;
  jitterMs: number;
  roundTripTimeMs: number;
}

export interface DecoderStats {
  averageDecodeLatencyMs: number;
  maxDecodeLatencyMs: number;
  keyFramesDecoded: number;
  droppedFrames: number;
  retainedBuffers: number;
  hwAccelerated: boolean;
}

export interface FramePipelineStats {
  queueSize: number;
  deliveredFrames: number;
  droppedFrames: number;
  averageProcessingTimeMs: number;
  maxProcessingTimeMs: number;
  lastFrameTimestampMs: number;
}

export interface CameraCapabilities {
  apiType: CameraApiType;
  hardwareLevel: string;
  supportedResolutions: string[];
  supportedFpsRanges: string[];
  supportsSurfaceTexture: boolean;
  supportsImageReader: boolean;
  requiresInstrumentation: boolean;
  cameraCount: number;
}

export interface TargetProfile {
  id: string;
  appName: string;
  packageName: string;
  minAndroidVersion: number;
  targetAndroidVersion: number;
  cameraApi: CameraApiType;
  expectedPreviewPath: string;
  supportedBackend: string;
  integrationMechanism: string;
  requiredPermissions: string[];
  requiresInstrumentation: boolean;
  requiresRoot: boolean;
  requiresPrivilegedAccess: boolean;
  knownLimitations: string;
  diagnosticInfo: string;
  testStatus: CameraVirtualizationStatus;
}

export interface DiagnosticsEvent {
  timestampMs: number;
  subsystem: Subsystem;
  level: DiagnosticsLevel;
  message: string;
  errorDetails?: string;
}

export interface DiagnosticsSnapshot {
  timestamp: number;
  // Transport Layer
  transportStatus: string;
  transportType: string;
  rtspUrl: string;
  reconnectCount: number;
  streamStats: StreamStats;

  // Decoder Layer
  decoderStatus: string;
  decoderResolution: string;
  decoderFps: number;
  decodedFrames: number;
  decoderDroppedFrames: number;
  decodeErrors: number;
  decoderStats: DecoderStats;

  // Frame Pipeline Layer
  pipelineStatus: string;
  frameHealthState: FrameHealthState;
  msSinceLastFrame: number;
  deliveredFrames: number;
  pipelineDroppedFrames: number;
  pipelineLatencyMs: number;
  activeConsumers: number;
  pipelineStats: FramePipelineStats;

  // Service & IPC Layer
  serviceRuntimeState: string;
  ipcProviderStatus: string;

  // Camera API Layer
  detectedCameraApi: CameraApiType;
  cameraHardwareLevel: string;

  // Virtualization Backend Layer
  activeBackend: string;
  virtualizationStatus: CameraVirtualizationStatus;

  // Target Layer
  targetPackage: string;
  targetStatus: string;

  // Boundary Milestones Active Tracking
  activeBoundaries: BoundaryDiagnosticStage[];

  // Active Fault Identification
  faultSubsystem?: Subsystem;
  lastErrorMessage?: string;
}

export interface ZestoUiState {
  selectedTab: ZestoTab;
  streamConfig: StreamConfig;
  isConnecting: boolean;
  isConnected: boolean;
  isDecoding: boolean;
  isServiceRunning: boolean;
  isVirtualFeedActive: boolean;
  connectionTestResult: string | null;
  isTestingConnection: boolean;
  diagnosticsSnapshot: DiagnosticsSnapshot;
  eventLogs: DiagnosticsEvent[];
  cameraCapabilities: CameraCapabilities;
  targetProfiles: TargetProfile[];
  selectedTargetProfile: TargetProfile | null;
  profileSearchQuery: string;
  showModuleGuideDialog: boolean;
  showTestHarnessModal: boolean;
  exportedLogText: string | null;
  userNoticeMessage: string | null;
  inputSourceType: 'simulated_rtsp' | 'webcam' | 'test_pattern';
}
