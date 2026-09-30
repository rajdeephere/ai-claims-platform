/**
 * The insurance vocabulary the app uses, with how the app applies each term. Values (limits, points,
 * deadlines) match the backend: TriageRules, FraudScorer, ActivityType, demo users and policies.
 */
export interface Term {
  term: string;
  /** other names for the same thing */
  aka?: string;
  meaning: string;
  inThisApp: string;
  /** where to see it in the UI */
  seeIt?: string;
}

export interface TermGroup {
  id: string;
  title: string;
  icon: string;
  terms: Term[];
}

export const GLOSSARY: TermGroup[] = [
  {
    id: 'policy',
    title: 'Policy and cover',
    icon: 'description',
    terms: [
      {
        term: 'Policy',
        meaning: 'The insurance contract: who is insured, what is covered, up to how much, and for which dates.',
        inThisApp: 'Every claim names a policy number. It is checked in the background right after the claim is filed (the policy system is simulated).',
        seeIt: 'Report a loss: "Policy number" (demo: POL-AUTO-1001 for a car, POL-HOME-2001 for a home).',
      },
      {
        term: 'Policyholder',
        aka: 'insured',
        meaning: 'The person who holds the policy.',
        inThisApp: 'The demo claimant is the holder of the demo policies. If someone else files on a policy, the claim is flagged "holder mismatch".',
      },
      {
        term: 'Coverage',
        meaning: 'One part of a policy that pays for one kind of loss.',
        inThisApp: 'Car policies have collision, comprehensive (theft) and glass; home policies have dwelling (fire, water) and contents (burglary). The type of loss decides which coverage applies.',
        seeIt: 'Financials tab: each exposure shows its coverage.',
      },
      {
        term: 'Limit',
        meaning: 'The most a coverage will pay.',
        inThisApp: 'Stored with each coverage of the demo policies (for example 25,000 for collision).',
      },
      {
        term: 'Deductible',
        meaning: 'The part of a loss the policyholder pays themselves before the insurer pays.',
        inThisApp: 'Recorded on each policy (demo: 500 on the car policy, 1,000 on the home policy). It is not yet subtracted from payments automatically.',
      },
      {
        term: 'In force',
        meaning: 'The policy was active on the date of the loss.',
        inThisApp: 'Checked against the loss date. A lapsed or cancelled policy is flagged "policy not in force" and the claim goes to the Complex segment; the system never denies by itself.',
      },
    ],
  },
  {
    id: 'claim',
    title: 'The claim',
    icon: 'folder_open',
    terms: [
      {
        term: 'Claim',
        meaning: 'A request to the insurer to pay for a loss under a policy.',
        inThisApp: 'One loss event on one policy, with a claim number like CLM-2026-000042.',
      },
      {
        term: 'Loss',
        meaning: 'The event that caused the damage: an accident, a theft, a fire.',
        inThisApp: 'Recorded with a type (car collision, theft, glass; home fire, water damage, burglary), a date, a place and a description. The loss date can\'t be in the future.',
      },
      {
        term: 'FNOL',
        aka: 'first notice of loss',
        meaning: 'The first report of a loss to the insurer, which starts the claim.',
        inThisApp: 'Filed by the claimant in the portal, or by an adjuster for a report taken by phone. Filing twice by mistake creates only one claim (idempotency key).',
        seeIt: 'Claimant: Report a loss.',
      },
      {
        term: 'Triage',
        meaning: 'Sorting new claims by how much handling they need.',
        inThisApp: 'Automatic, after the policy check and the document assessment. Sets the segment and assigns an adjuster.',
      },
      {
        term: 'Segment',
        meaning: 'The handling track a claim is put on.',
        inThisApp: 'Fast track: estimate under 2,000, fraud score 20 or less, verified policy, no injuries. Complex: injuries, unverified policy, or estimate over 25,000. Standard: everything else.',
        seeIt: 'Work queue and claim header badges.',
      },
      {
        term: 'Assignment',
        meaning: 'Giving a claim to the adjuster who will own it.',
        inThisApp: 'To the adjuster with the fewest open claims. A supervisor can reassign; the adjuster\'s open tasks move with the claim.',
      },
      {
        term: 'Information request',
        meaning: 'The adjuster asks the claimant for something missing (photos, an estimate, a police report).',
        inThisApp: 'The claim waits (Awaiting info). The claimant is reminded after 3 days, supervisors are alerted after 7, and after 14 days the claim comes back to the adjuster to decide.',
        seeIt: 'Adjuster: "Ask the claimant". Claimant: the yellow box on the claim.',
      },
      {
        term: 'Claim status',
        meaning: 'Where the claim is in its life.',
        inThisApp: 'Submitted → Assessing → Open ⇄ Awaiting info, Open → SIU review → Open, Open → Closed, Closed → Open (reopen). Any other move is refused. Claimants see a simpler version: Received, In review, Action needed, and the outcome.',
      },
      {
        term: 'Close outcome',
        meaning: 'Why a claim was closed.',
        inThisApp: 'Paid (money went out), No payment, Denied (a supervisor approved a denial) or Withdrawn (by the claimant, only before any payment).',
      },
      {
        term: 'Reopen',
        meaning: 'Bringing a closed claim back into handling, for example after new information.',
        inThisApp: 'Supervisors only, with a reason. The adjuster gets a task to review it.',
      },
      {
        term: 'Denial',
        aka: 'declinature',
        meaning: 'The insurer refuses to pay the claim.',
        inThisApp: 'Never automatic. The adjuster (or SIU, after confirmed fraud) proposes it with a reason; a supervisor approves it, and only then is the claim closed as Denied.',
      },
    ],
  },
  {
    id: 'people',
    title: 'People and roles',
    icon: 'groups',
    terms: [
      {
        term: 'Claimant',
        meaning: 'The person making the claim, usually the policyholder.',
        inThisApp: 'Reports losses, uploads documents, answers questions and follows the status. Never sees fraud scores, reserves or internal notes.',
      },
      {
        term: 'Adjuster',
        aka: 'claims handler',
        meaning: 'The insurer\'s employee who investigates a claim and settles it.',
        inThisApp: 'Owns assigned claims: exposures, reserves, payments within their authority, questions to the claimant, SIU referrals, closing.',
      },
      {
        term: 'Supervisor',
        meaning: 'Senior claims staff who approve what is above an adjuster\'s authority and oversee the team.',
        inThisApp: 'Approves payments, reserves and every denial; reassigns and reopens claims; watches the dashboard and SLA breaches.',
      },
      {
        term: 'SIU',
        aka: 'Special Investigations Unit',
        meaning: 'The team that investigates suspected fraud.',
        inThisApp: 'Sees only claims referred to it, records the outcome, and can never make payments.',
      },
    ],
  },
  {
    id: 'money',
    title: 'Money',
    icon: 'payments',
    terms: [
      {
        term: 'Exposure',
        meaning: 'One thing the insurer may have to pay for on a claim: one coverage for one claimant, for example "vehicle damage of the insured". A claim can have several.',
        inThisApp: 'Created by the adjuster. Reserves and payments are always against an exposure; all exposures must be closed before the claim can close.',
        seeIt: 'Claim → Financials → Exposures.',
      },
      {
        term: 'Reserve',
        meaning: 'Money the insurer sets aside for the expected cost of an exposure, before paying it.',
        inThisApp: 'Set by the adjuster; lowering is always allowed, raising above your authority needs a supervisor. It can never be below what is paid or being paid. Closing an exposure releases what is left.',
      },
      {
        term: 'Paid',
        meaning: 'Money that has actually gone out.',
        inThisApp: 'The sum of issued payments on an exposure; never more than its reserve.',
      },
      {
        term: 'Committed',
        meaning: 'Money promised but not yet paid.',
        inThisApp: 'Payments waiting for approval or on their way to the bank. It counts against the reserve, so two payments can\'t together spend more than was set aside.',
      },
      {
        term: 'Available',
        meaning: 'What can still be paid from an exposure.',
        inThisApp: 'Reserve − paid − committed. A payment above it is refused.',
      },
      {
        term: 'Payment',
        aka: 'indemnity payment',
        meaning: 'Money paid to settle part of a claim, to the claimant or a supplier such as a garage.',
        inThisApp: 'Requested against an exposure with a payee. Sent to the (simulated) bank; if the bank doesn\'t answer, it is retried with the same key so it can never be paid twice.',
      },
      {
        term: 'Payee',
        meaning: 'Who receives a payment.',
        inThisApp: 'Typed on the payment request, for example "City Motors Pvt Ltd".',
      },
      {
        term: 'Authority limit',
        meaning: 'The largest amount a person may reserve or pay without someone else approving it.',
        inThisApp: 'Adjusters 5,000, supervisors 50,000. Read from the database on every check, so a change applies immediately.',
        seeIt: 'Financials and Approvals show "Your authority".',
      },
      {
        term: 'Maker-checker',
        aka: 'four-eyes principle',
        meaning: 'One person makes a request, a different person approves it.',
        inThisApp: 'Nobody can approve their own request, and the approver\'s own limit must cover the amount. Also enforced by the database.',
        seeIt: 'Supervisor: Approvals.',
      },
      {
        term: 'Approval request',
        meaning: 'A request waiting for a checker: a payment or reserve above the requester\'s limit, or a denial.',
        inThisApp: 'Decided once: approved, or rejected with a reason.',
      },
      {
        term: 'Recovery',
        meaning: 'Money that comes back to the insurer after a claim was paid.',
        inThisApp: 'Recorded on the claim with its source; only possible once something was paid.',
      },
      {
        term: 'Subrogation',
        meaning: 'After paying its customer, the insurer claims the money back from whoever caused the loss (or their insurer).',
        inThisApp: 'Recorded as a recovery from a third party or third-party insurer.',
      },
      {
        term: 'Salvage',
        meaning: 'Value recovered from damaged property, for example selling a written-off car.',
        inThisApp: 'Recorded as a recovery with the source Salvage.',
      },
    ],
  },
  {
    id: 'fraud',
    title: 'Fraud and investigation',
    icon: 'policy',
    terms: [
      {
        term: 'Fraud score',
        meaning: 'An estimate of how suspicious a claim looks.',
        inThisApp: '0 to 100, and every point has a reason: loss soon after the policy started (+25), a document already used on another claim (+30), a document dated before the loss (+15), several claims on the policy (+20), plus signals from the AI. At 70 or more a new claim goes to SIU.',
        seeIt: 'Claim → Overview → Fraud score; the reasons are in the timeline.',
      },
      {
        term: 'Risk signal',
        aka: 'red flag',
        meaning: 'A specific reason for suspicion.',
        inThisApp: 'Raised by the AI while reading a document: signs of editing, contradicts the claimant\'s description, text that tries to instruct the AI, or unreadable.',
        seeIt: 'Documents & AI tab (red boxes).',
      },
      {
        term: 'SIU referral',
        meaning: 'Sending a claim to the fraud investigators.',
        inThisApp: 'Automatic at triage for a score of 70 or more, or by an adjuster or supervisor with a reason. The claim is under SIU review until an outcome is recorded.',
      },
      {
        term: 'SIU hold',
        meaning: 'No money moves on a claim while it is being investigated.',
        inThisApp: 'Payments can\'t be requested, approved or sent during SIU review; reserves can still change. The claimant just sees "In review".',
      },
      {
        term: 'Investigation outcome',
        meaning: 'The investigator\'s conclusion.',
        inThisApp: 'Cleared: handling resumes and held payments go out. Confirmed: a denial is proposed, which a supervisor still decides.',
      },
    ],
  },
  {
    id: 'work',
    title: 'Work and deadlines',
    icon: 'task_alt',
    terms: [
      {
        term: 'Activity',
        aka: 'task, diary',
        meaning: 'A piece of follow-up work on a claim, with an owner and a due date.',
        inThisApp: 'Created automatically: first contact with the claimant, a claimant who hasn\'t answered, an SIU investigation, a payment the bank never confirmed, a reopened claim.',
        seeIt: 'My activities; claim → Activities.',
      },
      {
        term: 'Queue',
        meaning: 'Work that belongs to a team rather than one person.',
        inThisApp: 'Some activities go to the supervisors\' or the SIU queue; anyone with that role can pick them up.',
      },
      {
        term: 'SLA',
        aka: 'service level agreement',
        meaning: 'The time allowed for a piece of work.',
        inThisApp: 'For example: first contact within 24 hours (4 hours on the fast track).',
      },
      {
        term: 'Escalation',
        aka: 'SLA breach',
        meaning: 'Work not done in time is raised to someone\'s attention.',
        inThisApp: 'At the due time an unfinished activity becomes urgent and appears on the supervisor dashboard, counted per owner.',
      },
      {
        term: 'Audit trail',
        meaning: 'The record of who did what, when and why.',
        inThisApp: 'Every change is recorded with before and after values and a reason, and can never be edited.',
        seeIt: 'Claim → Timeline & notes.',
      },
    ],
  },
  {
    id: 'ai',
    title: 'AI assistance',
    icon: 'auto_awesome',
    terms: [
      {
        term: 'Document assessment',
        meaning: 'Reading a claim document to pull out the facts: what it is, amounts, dates, damage.',
        inThisApp: 'Done in the background by an LLM (Groq) after each upload. Its answer is checked like any untrusted input before it is stored.',
        seeIt: 'Documents & AI tab.',
      },
      {
        term: 'Accept / correct',
        aka: 'override',
        meaning: 'A person confirms or fixes what the AI read.',
        inThisApp: 'Adjusters accept a result, or correct fields with a reason; the correction is what counts from then on, and the fraud score is recalculated.',
      },
      {
        term: 'Confidence',
        meaning: 'How sure the AI says it is about its answer.',
        inThisApp: 'Shown next to each result. It informs the reviewer; it never makes a decision.',
      },
    ],
  },
];
