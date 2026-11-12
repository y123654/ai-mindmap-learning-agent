export interface UserProfile {
  id: string
  username: string
  major: string
}

export interface AuthResponse {
  token: string
  user: UserProfile
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest extends LoginRequest {
  confirmPassword: string
  major: string
}
