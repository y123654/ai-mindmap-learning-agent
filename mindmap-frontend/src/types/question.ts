export type QuestionType = 'single_choice' | 'short_answer'
export type Difficulty = 'easy' | 'medium' | 'hard'
export type QuestionStatus = 'active' | 'pending_confirmation' | string

export interface QuestionOption {
  key: 'A' | 'B' | 'C' | 'D' | string
  text: string
}

export interface SingleChoiceAnswer {
  correctKeys: [string]
}

export interface ShortAnswer {
  referenceAnswer: string
}

export interface Question {
  id: string
  mindmapId: string
  nodeIds: string[]
  subject: string | null
  grade: string | null
  questionType: QuestionType | string
  difficulty: Difficulty | string
  stem: string
  options: QuestionOption[] | null
  answer: SingleChoiceAnswer | ShortAnswer | Record<string, unknown> | null
  explanation: string | null
  aiModel: string | null
  status: QuestionStatus
  createdAt: string
  updatedAt: string
}

export interface GenerateQuestionsRequest {
  subject: string
  grade?: string | null
  questionTypes: QuestionType[]
  difficulty: Difficulty
  count: number
  nodeIds?: string[]
}

export interface QuestionGenerationJob {
  jobId: string
  mindmapId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  request: GenerateQuestionsRequest
  resultQuestionIds: string[]
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
}


export interface QuestionListItem {
  id: string
  mindmapId: string
  documentId: string | null
  documentTitle: string
  subject: string | null
  grade: string | null
  questionType: QuestionType | string
  difficulty: Difficulty | string
  stem: string
  options: QuestionOption[] | null
  answer: SingleChoiceAnswer | ShortAnswer | Record<string, unknown> | null
  explanation: string | null
  nodeIds: string[]
  status: QuestionStatus
  createdAt: string
  updatedAt: string
}
