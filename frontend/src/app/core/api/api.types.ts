import type { components } from './api.schema';

/**
 * Types generated from the backend's committed OpenAPI contract (docs/openapi/api.v1.json, ADR-0008):
 * `npm run api:types` regenerates api.schema.d.ts. The generator marks every response field optional
 * (springdoc lists none as required), so responses are used through {@link Dto}; values the API
 * documents as nullable (closeOutcome, assignedAdjuster, openInfoRequest, ...) are still checked in templates.
 */
type Schemas = components['schemas'];
export type Dto<K extends keyof Schemas> = Required<Schemas[K]>;
export type Body<K extends keyof Schemas> = Schemas[K];

export type Role = Dto<'MeResponse'>['role'];
export type ClaimStatus = Dto<'StaffClaimResponse'>['status'];
export type ClaimantStatus = Dto<'PortalClaimResponse'>['status'];
export type ClaimAction = Dto<'StaffClaimResponse'>['allowedActions'][number];
export type LossType = Body<'FnolRequest'>['lossType'];
export type DocumentCategory = Body<'UploadUrlRequest'>['category'];

export type Me = Dto<'MeResponse'>;
export type TokenResponse = Dto<'TokenResponse'>;
export type ApiError = Schemas['ApiError'];

export type PortalClaim = Dto<'PortalClaimResponse'>;
export type PortalClaimSummary = Dto<'PortalClaimSummary'>;
export type StaffClaim = Dto<'StaffClaimResponse'>;
export type StaffClaimSummary = Dto<'StaffClaimSummary'>;
export type TimelineEntry = Dto<'TimelineEntryResponse'>;
export type Notification = Dto<'NotificationView'>;

export type PortalDocument = Dto<'PortalDocumentView'>;
export type StaffDocument = Dto<'StaffDocumentView'>;
export type UploadInstructions = Dto<'UploadInstructions'>;
export type AiAssessment = Dto<'AiAssessmentView'>;

export type Exposure = Dto<'ExposureResponse'>;
export type ReserveOutcome = Dto<'ReserveResponse'>;
export type Payment = Dto<'PaymentResponse'>;
export type Recovery = Dto<'RecoveryResponse'>;
export type Approval = Dto<'ApprovalResponse'>;

export type SiuCase = Dto<'SiuCaseResponse'>;
export type CaseFile = Dto<'CaseFileResponse'>;
export type Activity = Dto<'ActivityResponse'>;
export type Dashboard = Dto<'DashboardResponse'>;
export type Job = Dto<'JobView'>;

/** The API's page shape (common/web/PageResponse). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
