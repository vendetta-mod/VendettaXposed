const oldObjectCreate = this.Object.create;
globalThis.Object.create = (...args) => {
    const obj = oldObjectCreate.apply(globalThis.Object, args);
    if (args[0] === null) {
        globalThis.modules = obj;
        globalThis.Object.create = oldObjectCreate;
    }

    return obj;
};