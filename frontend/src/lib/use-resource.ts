"use client";
import { useCallback, useEffect, useState } from "react";
export function useResource<T>(key: string, load: () => Promise<T>) {
  const [version, setVersion] = useState(0);
  const requestKey = `${key}:${version}`;
  const [state, setState] = useState<{ key: string; data?: T; error?: unknown }>({ key: "" });
  useEffect(() => {
    let cancelled = false;
    load().then(data => { if (!cancelled) setState({ key: requestKey, data }); })
      .catch(error => { if (!cancelled) setState({ key: requestKey, error }); });
    return () => { cancelled = true; };
  }, [requestKey, load]);
  const reload = useCallback(() => setVersion(value => value + 1), []);
  const replace = useCallback((data: T) => setState({ key: requestKey, data }), [requestKey]);
  return { data: state.key === requestKey ? state.data : undefined, error: state.key === requestKey ? state.error : undefined,
    loading: state.key !== requestKey, reload, replace };
}
