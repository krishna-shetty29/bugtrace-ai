CREATE TABLE benchmark_browser_requests (
    request_id UUID PRIMARY KEY,
    case_id TEXT NOT NULL,
    registration_id UUID NOT NULL REFERENCES benchmark_case_registrations(id),
    investigation_id UUID NOT NULL UNIQUE REFERENCES investigations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
