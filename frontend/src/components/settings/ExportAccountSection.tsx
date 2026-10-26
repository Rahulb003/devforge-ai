import { useMutation } from '@tanstack/react-query';
import { Download } from 'lucide-react';

import { authApi } from '@/api/auth.api';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { describeApiError } from '@/lib/errors';

/**
 * Downloading your own account data. Fetched through the API client rather than a plain link,
 * because the endpoint needs the bearer token and a link would send none.
 */
export function ExportAccountSection() {
  const download = useMutation({
    mutationFn: async () => (await authApi.exportAccount()).data,
    onSuccess: (blob) => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'devforge-account.json';
      link.click();
      URL.revokeObjectURL(url);
    },
  });

  return (
    <Card>
      <CardHeader
        title="Export your data"
        description="Your profile, sessions, access tokens, sign-in history and audit trail, as JSON. Never includes passwords or token values."
      />
      <Button
        variant="secondary"
        leftIcon={<Download className="h-4 w-4" />}
        loading={download.isPending}
        onClick={() => download.mutate()}
      >
        Download account data
      </Button>
      {download.isError && (
        <p role="alert" className="mt-3 text-sm text-red-400">
          {describeApiError(download.error)}
        </p>
      )}
    </Card>
  );
}
