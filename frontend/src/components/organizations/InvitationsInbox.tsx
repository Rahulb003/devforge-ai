import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { membershipApi } from '@/api/membership.api';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { describeApiError } from '@/lib/errors';

/**
 * Invitations waiting for the signed-in user. Renders nothing when there are none, which is the
 * usual case, rather than an empty box on every visit.
 */
export function InvitationsInbox() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const invitations = useQuery({
    queryKey: ['my-invitations'],
    queryFn: async () => (await membershipApi.mine()).data.data,
  });

  const answered = () => {
    setError(null);
    void queryClient.invalidateQueries({ queryKey: ['my-invitations'] });
    void queryClient.invalidateQueries({ queryKey: ['organizations'] });
  };
  const accept = useMutation({
    mutationFn: (id: string) => membershipApi.accept(id),
    onSuccess: answered,
    onError: (err) => setError(describeApiError(err)),
  });
  const decline = useMutation({
    mutationFn: (id: string) => membershipApi.decline(id),
    onSuccess: answered,
    onError: (err) => setError(describeApiError(err)),
  });

  if (!invitations.isSuccess || invitations.data.length === 0) {
    return null;
  }

  return (
    <Card>
      <CardHeader title="Invitations" description="Organizations that have invited you to join." />
      {error && (
        <p role="alert" className="mb-3 text-sm text-red-400">
          {error}
        </p>
      )}
      <ul aria-label="Your invitations" className="space-y-2">
        {invitations.data.map((invitation) => (
          <li
            key={invitation.id}
            className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
          >
            <span className="text-slate-200">
              <span className="font-medium">{invitation.organizationName}</span>
              <span className="text-sm text-slate-400"> as {invitation.role.toLowerCase()}</span>
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                loading={accept.isPending && accept.variables === invitation.id}
                onClick={() => accept.mutate(invitation.id)}
                aria-label={`Join ${invitation.organizationName}`}
              >
                Join
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => decline.mutate(invitation.id)}
                aria-label={`Decline the invitation to ${invitation.organizationName}`}
              >
                Decline
              </Button>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}
