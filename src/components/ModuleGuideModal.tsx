import React from 'react';
import { X, BookOpen, CheckCircle, ShieldCheck, Terminal, AlertCircle } from 'lucide-react';

interface ModuleGuideModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const ModuleGuideModal: React.FC<ModuleGuideModalProps> = ({
  isOpen,
  onClose
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-3 sm:p-6 overflow-y-auto">
      <div className="bg-[#1A1C1E] border border-[#44474E] rounded-[28px] max-w-2xl w-full max-h-[90vh] overflow-y-auto shadow-2xl p-5 sm:p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-[#44474E] pb-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#D0BCFF]/20 text-[#D0BCFF] border border-[#D0BCFF]/40 flex items-center justify-center">
              <BookOpen className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-[#E2E2E6]">LSPatch & LSPosed Module Integration Guide</h3>
              <p className="text-xs text-[#8E919A]">
                Instructions for hooking third-party target applications
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-[#2D3033] hover:bg-[#44474E] text-[#C4C6CF] flex items-center justify-center transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Section 1: Non-Root Mode via LSPatch */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-2xl p-5 space-y-3">
          <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#34D399] uppercase">
            <CheckCircle className="w-4 h-4" />
            <span>Mode 1: Non-Root APK Patching (LSPatch Portable)</span>
          </div>

          <p className="text-xs text-[#C4C6CF] leading-relaxed">
            LSPatch allows patching target APKs (like Open Camera, Discord, WhatsApp) to embed the Zesto Xposed module without requiring device root.
          </p>

          <div className="bg-[#1A1C1E] border border-[#44474E] rounded-xl p-3.5 space-y-2 text-xs font-mono text-[#E2E2E6]">
            <div className="text-[#8E919A] flex items-center gap-1.5">
              <Terminal className="w-3.5 h-3.5 text-[#D0BCFF]" />
              <span>Step-by-step procedure:</span>
            </div>
            <ol className="list-decimal list-inside space-y-1.5 pl-1 text-[#C4C6CF]">
              <li>Install <span className="text-[#D0BCFF]">LSPatch Manager</span> on your device.</li>
              <li>Select target APK (e.g. <span className="text-[#34D399]">net.sourceforge.opencamera</span>).</li>
              <li>Choose <span className="text-[#D0BCFF]">Portable Mode</span> (embeds DexLoader).</li>
              <li>Select <span className="text-[#D0BCFF]">Zesto Module APK</span> from embedded module list.</li>
              <li>Install the repacked APK and grant Camera permissions.</li>
            </ol>
          </div>
        </div>

        {/* Section 2: Target App Patcher Manifest Integrity Inspector */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-2xl p-5 space-y-3">
          <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF] uppercase">
            <ShieldCheck className="w-4 h-4" />
            <span>Target Application Class Integrity & Crash Prevention</span>
          </div>

          <p className="text-xs text-[#C4C6CF] leading-relaxed">
            Zesto's <span className="font-mono text-[#D0BCFF]">TargetApplicationPatcherInspector</span> automatically validates target APK manifest application names, preventing <span className="font-mono text-[#EF4444]">ClassNotFoundException</span> when patched APKs specify nonexistent custom Application classes.
          </p>

          <div className="bg-[#1A1C1E] border border-[#44474E] rounded-xl p-3 text-[11px] font-mono text-[#8E919A]">
            <span className="text-[#34D399] font-bold">[PATCH_MANIFEST_APPLICATION]</span> Verified legitimate class resolution for Open Camera (<span className="text-[#E2E2E6]">MyApplication</span>), Discord (<span className="text-[#E2E2E6]">com.discord.app.App</span>), and Telegram (<span className="text-[#E2E2E6]">ApplicationLoader</span>).
          </div>
        </div>

        {/* Section 3: Root Mode via LSPosed */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-2xl p-5 space-y-3">
          <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#FBBF24] uppercase">
            <AlertCircle className="w-4 h-4" />
            <span>Mode 2: Root Mode (LSPosed / Magisk / KernelSU)</span>
          </div>

          <p className="text-xs text-[#C4C6CF] leading-relaxed">
            Required for apps with strict Play Integrity or HardwareBuffer verification (Instagram, Snapchat, Zoom). Enable Zesto in the LSPosed scope and reboot.
          </p>
        </div>

        {/* Dismiss button */}
        <button
          onClick={onClose}
          className="w-full py-3 rounded-xl bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] font-mono text-xs font-bold transition-all shadow"
        >
          GOT IT
        </button>
      </div>
    </div>
  );
};
