import CodeMirror from '@uiw/react-codemirror'
import { markdown } from '@codemirror/lang-markdown'

interface MarkdownEditorProps {
  value: string
  onChange: (value: string) => void
  editable?: boolean
}

export function MarkdownEditor({ value, onChange, editable = true }: MarkdownEditorProps) {
  return (
    <div className={`editor-wrap ${editable ? '' : 'is-locked'}`}>
      <CodeMirror
        value={value}
        height="100%"
        editable={editable}
        extensions={[markdown()]}
        onChange={onChange}
      />
    </div>
  )
}