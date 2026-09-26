import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Component } from 'react';
export class ErrorBoundary extends Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false };
    }
    static getDerivedStateFromError() {
        return { hasError: true };
    }
    componentDidCatch(error, errorInfo) {
        console.error('Unhandled error in UI:', error, errorInfo);
    }
    render() {
        if (this.state.hasError) {
            return (_jsxs("div", { className: "rounded-3xl border border-red-500 bg-slate-900 p-12 text-center text-white shadow-xl", children: [_jsx("p", { className: "text-xl font-semibold", children: "Something went wrong." }), _jsx("p", { className: "mt-3 text-slate-400", children: "Please refresh the application or contact your administrator." })] }));
        }
        return this.props.children;
    }
}
