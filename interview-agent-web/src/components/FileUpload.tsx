/**
 * @author: 公众号：IT杨秀才
 * @doc:后端，AI Agent知识进阶，后端、AI大模型、场景题面试大全：https://golangstar.cn/
 */

import { useCallback, useState } from 'react'

interface FileUploadProps {
  label: string
  accept: string
  onFileLoaded: (filename: string, base64: string) => void
}

export function FileUpload({ label, accept, onFileLoaded }: FileUploadProps) {
  const [dragOver, setDragOver] = useState(false)
  const [fileName, setFileName] = useState<string | null>(null)

  const handleFile = useCallback((file: File) => {
    setFileName(file.name)
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result as string).split(',')[1]
      onFileLoaded(file.name, base64)
    }
    reader.readAsDataURL(file)
  }, [onFileLoaded])

  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files[0]
    if (file) handleFile(file)
  }, [handleFile])

  return (
    <div
      onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
      onDragLeave={() => setDragOver(false)}
      onDrop={onDrop}
      className={`border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition ${
        dragOver ? 'border-blue-500 bg-blue-50' : 'border-gray-300 hover:border-gray-400'
      }`}
    >
      <input
        type="file"
        accept={accept}
        className="hidden"
        id={`upload-${label}`}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) handleFile(file)
        }}
      />
      <label htmlFor={`upload-${label}`} className="cursor-pointer">
        {fileName ? (
          <span className="text-green-600 font-medium">{fileName}</span>
        ) : (
          <span className="text-gray-500">{label}</span>
        )}
      </label>
    </div>
  )
}
