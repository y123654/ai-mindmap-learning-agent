import { QuestionRecordsView } from '../components/question/QuestionRecordsView'

interface QuestionRecordsPageProps {
  onOpenDocument: (documentId: string) => void
}

export function QuestionRecordsPage({ onOpenDocument }: QuestionRecordsPageProps) {
  return (
    <main className="page-workspace">
      <QuestionRecordsView onOpenDocument={onOpenDocument} />
    </main>
  )
}
