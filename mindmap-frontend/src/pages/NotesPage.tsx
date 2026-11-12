import { NotesView } from '../components/notes/NotesView'
import type { DocumentSummaryResponse } from '../types/document'

interface NotesPageProps {
  documents: DocumentSummaryResponse[]
  loading: boolean
  error: string
  currentDocumentId: string | null
  onOpenDocument: (id: string) => void
  onCreateDocument: () => void
  onRenameDocument: (id: string, title: string) => Promise<void>
  onDeleteDocument: (id: string) => Promise<void>
  /** 批量删除：一次删多篇，失败会抛错（页面负责提示） */
  onDeleteDocuments: (ids: string[]) => Promise<void>
  onDocumentsChanged: () => void | Promise<void>
}

export function NotesPage(props: NotesPageProps) {
  return (
    <main className="page-workspace">
      <NotesView {...props} />
    </main>
  )
}
