import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Mail, UserMinus } from 'lucide-react';
import { useState, type FormEvent } from 'react';

import { membershipApi } from '@/api/membership.api';
import type { OrganizationRole } from '@/api/project.api';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { ErrorState, Skeleton } from '@/components/ui/states';
import { describeApiError } from '@/lib/errors';
import { useAuthStore } from '@/stores/authStore';

const ROLES: OrganizationRole[] = ['OWNER', 'ADMIN', 'MEMBER'];

/**
 * Who is in the organization, and inviting more people.
 *
 * Everyone sees the members; owners and admins see the controls. The server decides every change
 * anyway - an admin cannot make an owner, and the last owner cannot be removed - and its refusal is
 * shown as it words it.
 */
export function MembersSection({
  organizationId,
  callerRole,
}: {
  organizationId: string;
  callerRole: OrganizationRole;
}) {
  const queryClient = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const canManage = callerRole === 'OWNER' || callerRole === 'ADMIN';
  const [email, setEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<OrganizationRole>('MEMBER');
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);

  const members = useQuery({
    queryKey: ['org-members', organizationId],
    queryFn: async () => (await membershipApi.members(organizationId)).data.data,
  });
  const pending = useQuery({
    queryKey: ['org-invitations', organizationId],
    queryFn: async () => (await membershipApi.pending(organizationId)).data.data,
    enabled: canManage,
  });

  const refresh = () => {
    setError(null);
    void queryClient.invalidateQueries({ queryKey: ['org-members', organizationId] });
    void queryClient.invalidateQueries({ queryKey: ['org-invitations', organizationId] });
  };
  const onError = (err: unknown) => setError(describeApiError(err));

  const invite = useMutation({
    mutationFn: () => membershipApi.invite(organizationId, email.trim(), inviteRole),
    onSuccess: () => {
      setSent(email.trim());
      setEmail('');
      refresh();
    },
    onError,
  });
  const changeRole = useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: OrganizationRole }) =>
      membershipApi.changeRole(organizationId, userId, role),
    onSuccess: refresh,
    onError,
  });
  const remove = useMutation({
    mutationFn: (userId: string) => membershipApi.remove(organizationId, userId),
    onSuccess: refresh,
    onError,
  });
  const revoke = useMutation({
    mutationFn: (invitationId: string) => membershipApi.revoke(organizationId, invitationId),
    onSuccess: refresh,
    onError,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setSent(null);
    if (email.trim()) invite.mutate();
  }

  return (
    <section aria-labelledby="members-heading" className="space-y-4">
      <h2 id="members-heading" className="text-lg font-semibold text-white">
        Members
      </h2>

      {error && (
        <p role="alert" className="text-sm text-red-400">
          {error}
        </p>
      )}

      {canManage && (
        <Card>
          <form
            onSubmit={submit}
            aria-label="Invite someone"
            className="flex flex-wrap items-end gap-3"
          >
            <div className="min-w-56 flex-1">
              <Input
                label="Invite by email"
                type="email"
                placeholder="colleague@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
            <label className="text-sm text-slate-300">
              <span className="mb-1.5 block font-medium">As</span>
              <select
                value={inviteRole}
                onChange={(e) => setInviteRole(e.target.value as OrganizationRole)}
                className="h-11 rounded-xl border border-slate-700 bg-slate-950 px-3 text-slate-100"
              >
                <option value="MEMBER">Member</option>
                <option value="ADMIN">Admin</option>
              </select>
            </label>
            <Button
              type="submit"
              loading={invite.isPending}
              disabled={!email.trim()}
              leftIcon={<Mail className="h-4 w-4" />}
            >
              Send invitation
            </Button>
          </form>
          {sent && (
            <p role="status" className="mt-3 text-sm text-emerald-300">
              Invitation sent to {sent}. It appears for them once they sign in with that address,
              verified.
            </p>
          )}
        </Card>
      )}

      {members.isLoading && <Skeleton className="h-24" />}
      {members.isError && (
        <ErrorState message={describeApiError(members.error)} onRetry={() => members.refetch()} />
      )}
      {members.isSuccess && (
        <ul aria-label="Members" className="space-y-2">
          {members.data.map((member) => {
            const isMe = member.userId === me?.id;
            const name = member.username ?? member.userId.slice(0, 8);
            return (
              <li
                key={member.userId}
                className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-800 bg-slate-950 px-4 py-3"
              >
                <div className="min-w-0">
                  <p className="font-medium text-slate-200">
                    {name}
                    {isMe && <span className="ml-2 text-xs text-slate-400">(you)</span>}
                  </p>
                  {member.email && <p className="text-xs text-slate-400">{member.email}</p>}
                </div>
                <div className="flex items-center gap-2">
                  {canManage && !isMe ? (
                    <select
                      aria-label={`Role of ${name}`}
                      value={member.role}
                      onChange={(e) =>
                        changeRole.mutate({
                          userId: member.userId,
                          role: e.target.value as OrganizationRole,
                        })
                      }
                      className="h-9 rounded-lg border border-slate-700 bg-slate-950 px-2 text-sm text-slate-100"
                    >
                      {ROLES.map((role) => (
                        <option key={role} value={role}>
                          {role}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <Badge>{member.role}</Badge>
                  )}
                  {(canManage || isMe) && (
                    <Button
                      variant="ghost"
                      size="sm"
                      leftIcon={<UserMinus className="h-4 w-4" />}
                      loading={remove.isPending && remove.variables === member.userId}
                      onClick={() => remove.mutate(member.userId)}
                      aria-label={isMe ? 'Leave this organization' : `Remove ${name}`}
                    >
                      {isMe ? 'Leave' : 'Remove'}
                    </Button>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {canManage && pending.isSuccess && pending.data.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-sm font-medium text-slate-300">Pending invitations</h3>
          <ul aria-label="Pending invitations" className="space-y-2">
            {pending.data.map((invitation) => (
              <li
                key={invitation.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-dashed border-slate-700 px-4 py-2 text-sm"
              >
                <span className="text-slate-300">
                  {invitation.email} · {invitation.role.toLowerCase()} · expires{' '}
                  {new Date(invitation.expiresAt).toLocaleDateString()}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => revoke.mutate(invitation.id)}
                  aria-label={`Revoke the invitation to ${invitation.email}`}
                >
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
