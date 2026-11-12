import { apiJson } from '../lib/http'
import type {
  GenerateQuestionsRequest,
  Question,
  QuestionGenerationJob,
  QuestionListItem
} from '../types/question'

export async function createQuestionGenerationJob(
  mindmapId: string,
  body: GenerateQuestionsRequest
): Promise<QuestionGenerationJob> {
  return apiJson(`/api/mindmaps/${mindmapId}/question-generation-jobs`, {
    method: 'POST',
    json: body
  }, '创建题目生成任务失败')
}

export async function getQuestionGenerationJob(jobId: string): Promise<QuestionGenerationJob> {
  return apiJson(`/api/question-generation-jobs/${jobId}`, {}, '查询题目生成任务失败')
}

export async function listAllQuestions(): Promise<QuestionListItem[]> {
  return apiJson('/api/questions', {}, '获取题目记录')
}

export async function listQuestions(mindmapId: string): Promise<Question[]> {
  return apiJson(`/api/mindmaps/${mindmapId}/questions`, {}, '获取题目失败')
}

export async function regenerateQuestion(questionId: string): Promise<Question> {
  return apiJson(`/api/questions/${questionId}/regenerate`, { method: 'POST' }, '重新生成题目失败')
}