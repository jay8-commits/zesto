import React, { useState } from 'react';
import { X, Copy, Check, Download, FileText } from 'lucide-react';

interface ExportLogModalProps {
  isOpen: boolean;
  onClose: () => void;
  logText: string;
}

export const ExportLogModal: React.FC<ExportLogModalProps> = ({
  isOpen,
  onClose,
  logText
}) => {
  const [copied, setCopied] = useState(false);

  if (!isOpen) return null;

  const handleCopy = () => {
    navigator.clipboard.writeText(logText).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const handleDownload = () => {
    const blob = new Blob([logText], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `zesto-diagnostics-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.md`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-3 sm:p-6 overflow-y-auto">
      <div className="bg-[#1A1C1E] border border-[#44474E] rounded-[28px] max-w-3xl w-full max-h-[90vh] overflow-y-auto shadow-2xl p-5 sm:p-6 space-y-5">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-[#44474E] pb-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#D0BCFF]/20 text-[#D0BCFF] border border-[#D0BCFF]/40 flex items-center justify-center">
              <FileText className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-[#E2E2E6]">Exported System Telemetry & Logs</h3>
              <p className="text-xs text-[#8E919A]">
                Markdown format with comprehensive multi-layer telemetry snapshot
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

        {/* Content Preview */}
        <div className="bg-[#111214] border border-[#44474E] rounded-2xl p-4 max-h-96 overflow-y-auto font-mono text-xs text-[#C4C6CF] leading-relaxed whitespace-pre select-all">
          {logText}
        </div>

        {/* Actions */}
        <div className="flex flex-col sm:flex-row items-center gap-3 pt-2">
          <button
            onClick={handleCopy}
            className="w-full sm:flex-1 py-3 rounded-xl bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] font-mono text-xs font-bold flex items-center justify-center gap-2 transition-all shadow"
          >
            {copied ? (
              <>
                <Check className="w-4 h-4 text-[#381E72]" />
                <span>COPIED TO CLIPBOARD!</span>
              </>
            ) : (
              <>
                <Copy className="w-4 h-4" />
                <span>COPY TO CLIPBOARD</span>
              </>
            )}
          </button>

          <button
            onClick={handleDownload}
            className="w-full sm:w-auto px-6 py-3 rounded-xl bg-[#2D3033] hover:bg-[#35393E] text-[#E2E2E6] border border-[#44474E] font-mono text-xs font-bold flex items-center justify-center gap-2 transition-all shadow"
          >
            <Download className="w-4 h-4" />
            <span>DOWNLOAD .MD</span>
          </button>
        </div>
      </div>
    </div>
  );
};
